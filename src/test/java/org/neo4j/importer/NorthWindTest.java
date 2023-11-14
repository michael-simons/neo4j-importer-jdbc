package org.neo4j.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NorthWindTest {

	protected final Neo4jContainer<?> neo4j = new Neo4jContainer<>(
		System.getProperty("neo4j-jdbc.default-neo4j-image"))
		.withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
		.waitingFor(Neo4jContainer.WAIT_FOR_BOLT)
		.withReuse(true);

	protected final DockerComposeContainer environment =
		new DockerComposeContainer(new File("src/test/resources/pg_northwind/docker-compose.yml"))
			.withExposedService("db", 5432);

	@BeforeAll
	void startDatabases() {
		this.environment.start();
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
	void getMetaDataShouldWork() throws SQLException {

		var app = new JdbcImporterApp();
		app.sourceUrl = "jdbc:postgresql://localhost:%d/northwind".formatted(environment.getServicePort("db", 5432));
		app.sourceUser = "postgres";
		app.sourcePassword = "postgres".toCharArray();

		var metadata = app.getSourceMetadata();
		assertThat(metadata).hasSize(14);
	}

	@Test
	void smokeTest() throws Exception {

		var app = new JdbcImporterApp();
		app.model = Paths.get(this.getClass().getResource("/pg_northwind/model.json").toURI()).toFile();

		app.alwaysQuote = true;
		app.batchSize = 1500;

		app.sourceUrl = "jdbc:postgresql://localhost:%d/northwind".formatted(environment.getServicePort("db", 5432));
		app.sourceUser = "postgres";
		app.sourcePassword = "postgres".toCharArray();

		app.targetUrl = URI.create(neo4j.getBoltUrl());
		app.targetUser = "neo4j";
		app.targetPassword = neo4j.getAdminPassword().toCharArray();

		app.call();
	}
}
