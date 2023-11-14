package org.neo4j.importer;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.neo4j.cypherdsl.support.schema_name.SchemaNames;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.importer.graph_schema.Extensions;
import org.neo4j.importer.graph_schema.ExtensionsModule;
import org.neo4j.importer.graph_schema.GraphSchema;
import org.neo4j.importer.graph_schema.GraphSchema.Ref;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

@CommandLine.Command(name = "neo4j-load-so", mixinStandardHelpOptions = true)
class JdbcImporterApp implements Callable<Integer> {

	@CommandLine.Option(
		names = {"-s", "--source"},
		description = "JDBC URL of the source database.",
		required = true
	)
	String sourceUrl;

	@CommandLine.Option(
		names = {"-su", "--source-username"},
		description = "The login of the user connecting to the source database."
	)
	String sourceUser;

	@CommandLine.Option(
		names = {"-sp", "--source-password"},
		description = "The password of the user connecting to the source database."
	)
	char[] sourcePassword;

	@CommandLine.Option(
		names = {"--source-schema"},
		description = "The source schema"
	)
	String sourceSchema;

	@CommandLine.Option(
		names = {"-a", "--address"},
		description = "The address of the Neo4j host.",
		required = true,
		defaultValue = "bolt://localhost:7687"
	)
	URI targetUrl;

	@CommandLine.Option(
		names = {"-u", "--username"},
		description = "The login of the user connecting to the target database.",
		required = true,
		defaultValue = "neo4j"
	)
	String targetUser;

	@CommandLine.Option(
		names = {"-p", "--password"},
		description = "The password of the user connecting to the target database.",
		required = true,
		defaultValue = "verysecret"
	)
	char[] targetPassword;

	@CommandLine.Option(
		names = {"--batch-size"},
		description = "Batch size to use",
		required = true,
		defaultValue = "50000"
	)
	int batchSize;

	@CommandLine.Option(
		names = {"--always-quote"},
		description = "Whether to always quote identifiers",
		defaultValue = "true"
	)
	boolean alwaysQuote;

	@CommandLine.Parameters
	File model;

	private final ObjectMapper objectMapper;

	public static void main(String... args) {
		var commandLine = new CommandLine(new JdbcImporterApp());
		commandLine.setCaseInsensitiveEnumValuesAllowed(true);

		int exitCode = commandLine.execute(args);
		System.exit(exitCode);
	}

	JdbcImporterApp() {
		this.objectMapper = new ObjectMapper();
		this.objectMapper.registerModule(new ExtensionsModule());
	}

	@Override
	public Integer call() throws Exception {

		var model = buildMapping();
		var metada = getSourceMetadata();
		try (
			var jdbcConnection = getSourceConnection();
			var hlp = jdbcConnection.createStatement();
			var neo4jConnection = GraphDatabase.driver(targetUrl, AuthTokens.basic(targetUser, new String(targetPassword)));
		) {
			UnaryOperator<String> sqlQuotation = v -> {
				try {
					return hlp.enquoteIdentifier(v, alwaysQuote);
				} catch (SQLException e) {
					throw new RuntimeException(e);
				}
			};
			UnaryOperator<String> cypherQuotation = v -> SchemaNames.sanitize(v, alwaysQuote).orElseThrow();

			createIndexes(model, neo4jConnection, cypherQuotation);
			importNodes(model, metada, sqlQuotation, cypherQuotation, jdbcConnection, neo4jConnection);
			importRelationships(model, sqlQuotation, cypherQuotation, jdbcConnection, neo4jConnection);
		}

		return 0;
	}

	private Connection getSourceConnection() throws SQLException {
		return DriverManager.getConnection(sourceUrl, sourceUser, sourcePassword == null ? null : new String(sourcePassword));
	}

	private void importRelationships(MappingModel model, UnaryOperator<String> sqlQuotation, UnaryOperator<String> cypherQuotation, Connection jdbcConnection, Driver neo4jConnection) throws SQLException {

		for (var relationshipMapping : model.relationshipMappings()) {
			var sourceQuery = createSourceQuery(sqlQuotation, relationshipMapping);
			var targetQuery = createTargetQuery(cypherQuotation, relationshipMapping);

			int offset = 0;
			var batch = new ArrayList<Map<String, Object>>();
			try (var statement = jdbcConnection.prepareStatement(sourceQuery)) {
				do {
					batch.clear();

					statement.setInt(1, batchSize);
					statement.setInt(2, offset);

					try (var result = statement.executeQuery()) {
						while (result.next()) {
							var properties = new HashMap<String, Object>(relationshipMapping.propertyMappings().size());
							for (var propertyMapping : relationshipMapping.propertyMappings()) {
								properties.put(propertyMapping.to, result.getObject(propertyMapping.from));
							}
							var fromId = result.getObject(relationshipMapping.sourceNode.column());
							var toId = result.getObject(relationshipMapping.targetNode().column());
							batch.add(Map.of("sourceProperty", fromId, "targetProperty", toId, "properties", properties));
						}
					}
					offset += batch.size();

					if (!batch.isEmpty()) {
						var counters = neo4jConnection.executableQuery(targetQuery)
							.withParameters(Map.of("rows", batch))
							.execute()
							.summary().counters();
						System.out.println(counters);
					}
				} while (!batch.isEmpty());
			}
		}
	}

	private void importNodes(MappingModel model, List<Table> metadata, UnaryOperator<String> sqlQuotation, UnaryOperator<String> cypherQuotation, Connection jdbcConnection, Driver neo4jConnection) throws SQLException {

		var tables = metadata.stream().collect(Collectors.toMap(Table::name, Function.identity()));

		for (var nodeMapping : model.nodeMappings()) {
			var sourceQuery = createSourceQuery(sqlQuotation, nodeMapping);
			var targetQuery = createTargetNodeQuery(cypherQuotation, nodeMapping);

			// Import nodes
			Object primaryKey = null;
			try (var statement = jdbcConnection.prepareStatement(sourceQuery)) {
				do {
					var batch = new ArrayList<Map<String, Object>>();
					if (primaryKey == null) {
						var pkCol = tables.get(nodeMapping.source()).columns().stream().filter(Column::isPrimaryKey).findFirst().orElseThrow();
						var type = pkCol.type().getVendorTypeNumber();
						statement.setNull(1, type);
						statement.setNull(2, type);
					} else {
						statement.setObject(1, primaryKey);
						statement.setObject(2, primaryKey);
					}
					statement.setInt(3, batchSize);
					primaryKey = null;

					try (var result = statement.executeQuery()) {
						while (result.next()) {
							var properties = new HashMap<String, Object>(nodeMapping.propertyMappings().size());
							for (var propertyMapping : nodeMapping.propertyMappings()) {
								properties.put(propertyMapping.to, result.getObject(propertyMapping.from));
							}
							primaryKey = result.getObject(nodeMapping.primaryKey().from);
							batch.add(Map.of("primaryKey", primaryKey, "properties", properties));
						}
					}

					if (!batch.isEmpty()) {
						var counters = neo4jConnection.executableQuery(targetQuery)
							.withParameters(Map.of("rows", batch))
							.execute()
							.summary().counters();
						System.out.println(counters);
					}
				} while (primaryKey != null);
			}
		}
	}

	private static void createIndexes(MappingModel model, Driver neo4jConnection, UnaryOperator<String> cypherQuotation) {
		for (var nodeMapping : model.nodeMappings()) {
			neo4jConnection.executableQuery(createConstraintQuery(cypherQuotation, nodeMapping))
				.execute();
		}
		neo4jConnection.executableQuery("CALL db.awaitIndexes()").execute();
	}

	private static String createConstraintQuery(UnaryOperator<String> cypherQuotation, NodeMapping nodeMapping) {

		var uniqueProperty = nodeMapping.primaryKey().to();
		return """
			CREATE CONSTRAINT %s IF NOT EXISTS
			FOR (n:%s)
			REQUIRE (n.%s) IS UNIQUE;
			""".formatted(
			cypherQuotation.apply("imp_uniq_" + nodeMapping.label() + "_" + uniqueProperty),
			cypherQuotation.apply(nodeMapping.label()), cypherQuotation.apply(uniqueProperty)
		);
	}

	private static String createSourceQuery(UnaryOperator<String> sqlQuotation, NodeMapping nodeMapping) {
		var tableName = sqlQuotation.apply(nodeMapping.source());
		var selectList = nodeMapping.propertyMappings()
			.stream()
			.map(PropertyMapping::from)
			.map(sqlQuotation)
			.collect(Collectors.joining(", "));
		var primaryKey = sqlQuotation.apply(nodeMapping.primaryKey().from);

		return """
			SELECT %s FROM %s
			WHERE ? IS NULL OR %3s > ?
			ORDER BY %3$s
			LIMIT ?
			""".formatted(selectList, tableName, primaryKey);
	}

	private static String createSourceQuery(UnaryOperator<String> sqlQuotation, RelationshipMapping relationshipMapping) {
		var tableName = sqlQuotation.apply(relationshipMapping.source());
		var sourceColumn = relationshipMapping.sourceNode().column();
		var targetColumn = relationshipMapping.targetNode().column();
		var selectList =
			Stream.concat(
					Stream.of(sourceColumn, targetColumn),
					relationshipMapping.propertyMappings().stream().map(PropertyMapping::from)
				)
				.map(sqlQuotation)
				.collect(Collectors.joining(", "));
		return """
			SELECT %s FROM %s
			ORDER BY %s, %s
			LIMIT ? OFFSET ?
			""".formatted(selectList, tableName, sqlQuotation.apply(sourceColumn), sqlQuotation.apply(targetColumn));
	}

	private static String createTargetNodeQuery(UnaryOperator<String> cypherQuotation, NodeMapping nodeMapping) {
		var labelName = cypherQuotation.apply(nodeMapping.label());
		var primaryKey = cypherQuotation.apply(nodeMapping.primaryKey().to);

		return """
			UNWIND $rows AS row
			MERGE (n:%s {%s: row.primaryKey})
			SET n = row.properties
			""".formatted(labelName, primaryKey);
	}

	private static String createTargetQuery(UnaryOperator<String> cypherQuotation, RelationshipMapping relationshipMapping) {
		var sourceLabel = cypherQuotation.apply(relationshipMapping.sourceNode().label());
		var sourceId = cypherQuotation.apply(relationshipMapping.sourceNode().property());
		var targetLabel = cypherQuotation.apply(relationshipMapping.targetNode().label());
		var targetId = cypherQuotation.apply(relationshipMapping.targetNode().property());
		var type = cypherQuotation.apply(relationshipMapping.type());

		return """
			UNWIND $rows AS row
			MATCH (source: %s {%s: row.sourceProperty})
			MATCH (target: %s {%s: row.targetProperty})
			MERGE (source)-[r:%s]->(target)
			SET r = row.properties
			""".formatted(sourceLabel, sourceId, targetLabel, targetId, type);
	}

	@SuppressWarnings("unchecked")
	MappingModel buildMapping() throws IOException {

		var raw = this.objectMapper.readValue(model, new TypeReference<HashMap<String, Object>>() {
		});

		var dataModel = raw.containsKey("dataModel") ? ((Map<String, Object>) raw.get("dataModel")) : raw;

		var graphSchemaRepresentation = (Map<String, Object>) dataModel.get("graphSchemaRepresentation");
		var graphSchema = objectMapper.convertValue(graphSchemaRepresentation.get("graphSchema"), GraphSchema.class);

		var nodeKeys = objectMapper.convertValue(dataModel.get("graphSchemaExtensionsRepresentation"), Extensions.class)
			.getNodeKeyProperties().stream()
			.map(Extensions.KeyProperty::property)
			.collect(Collectors.toSet());

		var graphMappingRepresentation = (Map<String, Object>) dataModel.get("graphMappingRepresentation");
		Map<Ref, String> files;
		if (graphMappingRepresentation.containsKey("fileSchemas")) {
			files = ((List<Map<String, Object>>) graphMappingRepresentation.get("fileSchemas"))
				.stream().collect(Collectors.toMap(m -> new Ref((String) m.get("$id")), m -> (String) m.get("fileName")));
		} else if (graphMappingRepresentation.containsKey("dataSourceSchema")) {
			files = ((List<Map<String, Object>>) ((Map<String, Object>) graphMappingRepresentation.get("dataSourceSchema")).get("entities"))
				.stream().collect(Collectors.toMap(m -> new Ref((String) m.get("$id")), m -> (String) m.get("name")));
		} else {
			throw new RuntimeException("No schema");
		}

		var nodeMappings = ((List<Map<String, Object>>) graphMappingRepresentation.get("nodeMappings"))
			.stream().map(nodeMappingMap -> {
				var source = computeTableName(nodeMappingMap, files);

				var nodeObjectType = graphSchema.nodeObjectTypes().get(objectMapper.convertValue(nodeMappingMap.get("node"), Ref.class));
				var label = graphSchema.nodeLabels().get(nodeObjectType.labels().get(0).value()).value();

				var propertyMappings = buildPropertyMappings(nodeMappingMap, nodeObjectType, nodeKeys);
				return new NodeMapping(source, label, propertyMappings);
			}).collect(Collectors.toMap(NodeMapping::label, Function.identity()));

		var relationshipMappings = ((List<Map<String, Object>>) graphMappingRepresentation.get("relationshipMappings"))
			.stream().map(relMappingMap -> {
				var source = computeTableName(relMappingMap, files);

				var relationshipObjectType = graphSchema.relationshipObjectTypes().get(objectMapper.convertValue(relMappingMap.get("relationship"), Ref.class));
				var type = graphSchema.relationshipTypes().get(relationshipObjectType.type().value()).value();

				var sourceNode = makeNodeRef((Map<String, String>) relMappingMap.get("fromMapping"), graphSchema, graphSchema.nodeObjectTypes().get(relationshipObjectType.from()), nodeMappings);
				var targetNode = makeNodeRef((Map<String, String>) relMappingMap.get("toMapping"), graphSchema, graphSchema.nodeObjectTypes().get(relationshipObjectType.to()), nodeMappings);

				var propertyMappings = buildPropertyMappings(relMappingMap, relationshipObjectType, nodeKeys);
				return new RelationshipMapping(source, type, sourceNode, targetNode, propertyMappings);
			}).toList();

		return new MappingModel(nodeMappings.values().stream().toList(), relationshipMappings);

	}

	private String computeTableName(Map<String, Object> relMappingMap, Map<Ref, String> files) {

		Ref schemaRef;
		if (relMappingMap.containsKey("fileSchema")) {
			schemaRef = objectMapper.convertValue(relMappingMap.get("fileSchema"), Ref.class);
		} else if (relMappingMap.containsKey("dataSourceEntity")) {
			schemaRef = objectMapper.convertValue(relMappingMap.get("dataSourceEntity"), Ref.class);
		} else {
			throw new RuntimeException("No file schema or data source entity mapping");
		}
		return files.get(schemaRef).replaceAll("(?i)\\.[ct]sv", "");
	}

	@SuppressWarnings("unchecked")
	private List<PropertyMapping> buildPropertyMappings(Map<String, Object> relMappingMap, GraphSchema.HasProperties hasProperties, Set<Ref> nodeKeys) {
		return ((List<Map<String, Object>>) relMappingMap.get("propertyMappings"))
			.stream().map(propertyMappingMap -> {
				var propertyRef = objectMapper.convertValue(propertyMappingMap.get("property"), Ref.class);
				var property = hasProperties.propertyBy(propertyRef).orElseThrow();
				return new PropertyMapping((String) propertyMappingMap.get("fieldName"), property.token(), nodeKeys.contains(new Ref(property.id())));
			}).toList();
	}

	static NodeRef makeNodeRef(Map<String, String> mapping, GraphSchema graphSchema, GraphSchema.NodeObjectType referenceNode, Map<String, NodeMapping> nodeMappings) {

		var targetLabel = graphSchema.nodeLabels().get(referenceNode.labels().get(0).value()).value();
		var targetId = nodeMappings.get(targetLabel).primaryKey().to();
		var targetField = mapping.get("fieldName");

		return new NodeRef(targetLabel, targetId, targetField);
	}

	record PropertyMapping(String from, String to, boolean isPrimaryKey) {

		PropertyMapping(String from, String to) {
			this(from, to, false);
		}
	}

	record NodeMapping(String source, String label, List<PropertyMapping> propertyMappings) {

		PropertyMapping primaryKey() {
			return propertyMappings.stream().filter(PropertyMapping::isPrimaryKey)
				.findFirst().orElseThrow(() -> new IllegalStateException("No primary key for mapping %s to %s".formatted(source, label)));
		}
	}

	record NodeRef(String label, String property, String column) {
	}

	record RelationshipMapping(
		String source,
		String type,
		NodeRef sourceNode,
		NodeRef targetNode,
		List<PropertyMapping> propertyMappings) {
	}

	record MappingModel(
		List<NodeMapping> nodeMappings,
		List<RelationshipMapping> relationshipMappings
	) {
	}

	@CommandLine.Command(name = "print-metadata")
	void printMetaData() {
		try {
			getSourceMetadata().forEach(System.out::println);
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	record Column(String name, JDBCType type, String typeName, boolean isNullable, boolean isPrimaryKey) {

	}

	record Table(String name, List<Column> columns) {
	}

	/**
	 * Constants used in this method are constants from JDBC.
	 *
	 * @throws SQLException
	 */
	List<Table> getSourceMetadata() throws SQLException {

		var result = new ArrayList<Table>();
		try (
			var jdbcConnection = getSourceConnection()
		) {
			var databaseMetadata = jdbcConnection.getMetaData();

			try (var tables = databaseMetadata.getTables(null, this.sourceSchema, null, new String[] {"BASE TABLE", "TABLE"})) {

				while (tables.next()) {
					var tableName = tables.getString("TABLE_NAME");
					var primaryKeys = new HashSet<String>();
					try (var rs = databaseMetadata.getPrimaryKeys(null, this.sourceSchema, tableName)) {
						while (rs.next()) {
							primaryKeys.add(rs.getString("COLUMN_NAME"));
						}
					}

					var columns = new ArrayList<Column>();
					try (var rs = databaseMetadata.getColumns(null, this.sourceSchema, tableName, null)) {
						while (rs.next()) {
							var columnName = rs.getString("COLUMN_NAME");
							var columnType = JDBCType.valueOf(rs.getInt("DATA_TYPE"));
							var columnTypeName = rs.getString("TYPE_NAME");
							columns.add(new Column(columnName, columnType, columnTypeName, isNullable(rs), primaryKeys.contains(columnName)));
						}
					}
					result.add(new Table(tableName, List.copyOf(columns)));
				}
			}
		}

		return List.copyOf(result);
	}

	private static boolean isNullable(ResultSet columnMeta) throws SQLException {
		var r = columnMeta.getString("IS_NULLABLE");
		if (columnMeta.wasNull()) {
			r = columnMeta.getString("NULLABLE");
		}
		return switch (r) {
			case "columnNoNulls", "NO" -> false;
			case "columnNullable", "columnNullableUnknown", "YES", "" -> true;
			default -> false;
		};
	}
}
