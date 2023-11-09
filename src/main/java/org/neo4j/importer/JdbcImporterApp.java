package org.neo4j.importer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.neo4j.cypherdsl.support.schema_name.SchemaNames;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

class JdbcImporterApp implements Callable<Integer> {

	private String sourceUrl = "jdbc:duckdb:./test.db";

	private String targetUrl = "neo4j://localhost:7687";

	private String targetUser = "neo4j";

	private String targetPassword = "verysecret";

	private int batchSize = 1000;

	private boolean alwaysQuote = true;

	public static void main(String... args) throws Exception {
		new JdbcImporterApp().call();
	}

	@Override
	public Integer call() throws Exception {

		var nodeMappings = List.of(
			new NodeMapping(
				"states", "State",
				List.of(
					new PropertyMapping("state", "state", true),
					new PropertyMapping("latitude", "latitude"),
					new PropertyMapping("longitude", "longitude"),
					new PropertyMapping("name", "name")
				)
			),
			new NodeMapping(
				"us_counties", "County",
				List.of(
					new PropertyMapping("us_county", "us_county", true),
					new PropertyMapping("name", "name"),
					new PropertyMapping("state", "state")
				)
			));

		var relationshipMappings = List.of(
			new RelationshipMapping(
				"us_counties",
				"HAS",
				"State", "state",
				"County", "us_county",
				List.of()
			)
		);

		try (
			var jdbcConnection = DriverManager.getConnection(sourceUrl);
			var hlp = jdbcConnection.createStatement();
			var neo4jConnection = GraphDatabase.driver(targetUrl, AuthTokens.basic(targetUser, targetPassword));
		) {
			UnaryOperator<String> sqlQuotation = v -> {
				try {
					return hlp.enquoteIdentifier(v, alwaysQuote);
				} catch (SQLException e) {
					throw new RuntimeException(e);
				}
			};
			UnaryOperator<String> cypherQuotation = v -> SchemaNames.sanitize(v, alwaysQuote).orElseThrow();

			for (var nodeMapping : nodeMappings) {
				var sourceQuery = createSourceQuery(sqlQuotation, nodeMapping);
				var targetQuery = createTargetNodeQuery(cypherQuotation, nodeMapping);

				// Import nodes
				Object primaryKey = null;
				try (var statement = jdbcConnection.prepareStatement(sourceQuery)) {
					do {
						var batch = new ArrayList<Map<String, Object>>();

						statement.setObject(1, primaryKey);
						statement.setObject(2, primaryKey);
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

			for (var relationshipMapping : relationshipMappings) {
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
								var fromId = result.getObject(relationshipMapping.sourceId());
								var toId = result.getObject(relationshipMapping.targetId());
								batch.add(Map.of("sourceId", fromId, "targetId", toId, "properties", properties));
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

		return 0;
	}

	static String createSourceQuery(UnaryOperator<String> sqlQuotation, NodeMapping nodeMapping) {
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

	static String createSourceQuery(UnaryOperator<String> sqlQuotation, RelationshipMapping relationshipMapping) {
		var tableName = sqlQuotation.apply(relationshipMapping.source());
		var selectList =
			Stream.concat(
					Stream.of(relationshipMapping.sourceId(), relationshipMapping.targetId()),
					relationshipMapping.propertyMappings().stream().map(PropertyMapping::from)
				)
				.map(sqlQuotation)
				.collect(Collectors.joining(", "));
		var fromId = sqlQuotation.apply(relationshipMapping.sourceId());
		var toId = sqlQuotation.apply(relationshipMapping.targetId());

		return """
			SELECT %s FROM %s
			ORDER BY %s, %s
			LIMIT ? OFFSET ?
			""".formatted(selectList, tableName, fromId, toId);
	}

	static String createTargetNodeQuery(UnaryOperator<String> cypherQuotation, NodeMapping nodeMapping) {
		var labelName = cypherQuotation.apply(nodeMapping.label());
		var primaryKey = cypherQuotation.apply(nodeMapping.primaryKey().to);

		return """
			UNWIND $rows AS row
			MERGE (n:%s {%s: row.primaryKey})
			SET n = row.properties
			""".formatted(labelName, primaryKey);
	}

	static String createTargetQuery(UnaryOperator<String> cypherQuotation, RelationshipMapping relationshipMapping) {
		var sourceLabel = cypherQuotation.apply(relationshipMapping.sourceLabel());
		var sourceId = cypherQuotation.apply(relationshipMapping.sourceId());
		var targetLabel = cypherQuotation.apply(relationshipMapping.targetLabel());
		var targetId = cypherQuotation.apply(relationshipMapping.targetId());
		var type = cypherQuotation.apply(relationshipMapping.type());

		return """
			UNWIND $rows AS row
			MATCH (source: %s {%s: row.sourceId})
			MATCH (target: %s {%s: row.targetId})
			MERGE (source)-[r:%s]->(target)
			SET r = row.properties
			""".formatted(sourceLabel, sourceId, targetLabel, targetId, type);
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

	record RelationshipMapping(
		String source,
		String type,
		String sourceLabel, String sourceId,
		String targetLabel, String targetId,
		List<PropertyMapping> propertyMappings) {
	}
}
