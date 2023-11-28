package org.neo4j.importer;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParquetViaDuckDBAppTest {

	protected final Neo4jContainer<?> neo4j = new Neo4jContainer<>(
		System.getProperty("neo4j-jdbc.default-neo4j-image"))
		.withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
		.waitingFor(Neo4jContainer.WAIT_FOR_BOLT)
		.withReuse(true);

	@BeforeAll
	void startNeo4j() {
		this.neo4j.start();
	}

	@BeforeEach
	void clearNeo4j() {

		try (var driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", neo4j.getAdminPassword()));
		var session = driver.session()) {
			session.run("""
				MATCH (n)
				CALL {
					WITH n DETACH DELETE n
				}
				IN TRANSACTIONS OF $rows ROWS"""
			, Map.of("rows", 10000)).consume();
		}
	}

	@Test
	void smokeTest() throws Exception {

		var app = new JdbcImporterApp();
		app.model = Paths.get(this.getClass().getResource("/states_parquet/model.json").toURI()).toFile();

		app.alwaysQuote = true;
		app.batchSize = 1500;

		// The application will issue queries like `select * from states.parquet` and duckdb will do the rest
		// DuckDB can read parquet, csv and json as if they were tables
		// get our free book here https://motherduck.com/duckdb-book/

		app.sourceUrl = "jdbc:duckdb:"; // Just use an in-memory database
		app.targetUrl = URI.create(neo4j.getBoltUrl());
		app.targetUser = "neo4j";
		app.targetPassword = neo4j.getAdminPassword().toCharArray();
		app.call();
	}
}