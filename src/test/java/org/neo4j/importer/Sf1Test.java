package org.neo4j.importer;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Sf1Test {

	private final Neo4jContainer<?> neo4j = new Neo4jContainer<>(
		System.getProperty("neo4j-jdbc.default-neo4j-image"))
		.withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
		.waitingFor(Neo4jContainer.WAIT_FOR_BOLT)
		.withReuse(false);

	@BeforeEach
	void startNeo4j() {
		this.neo4j.start();
	}

	@AfterEach
	void stopNeo4j() {
		this.neo4j.stop();
	}

	@ParameterizedTest
	@ValueSource(ints = {5000, 20000, 50000})
	void smokeTest(int batchsize) throws Exception {

		var app = new JdbcImporterApp();
		app.model = Paths.get(this.getClass().getResource("/ldbc/model.json").toURI()).toFile();

		app.alwaysQuote = true;
		app.batchSize = batchsize;

		app.sourceUrl = "jdbc:postgresql://localhost:5432/ldbc";
		app.sourceUser = "postgres";
		app.sourcePassword = "mysecretpassword".toCharArray();

		app.targetUrl = URI.create("neo4j://localhost:%d".formatted(neo4j.getMappedPort(7687)));
		app.targetUser = "neo4j";
		app.targetPassword = neo4j.getAdminPassword().toCharArray();

		app.call();
	}
}
