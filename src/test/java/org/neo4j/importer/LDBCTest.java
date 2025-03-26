package org.neo4j.importer;

import java.net.URI;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.github.cdimascio.dotenv.Dotenv;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LDBCTest {

	private final Neo4jContainer<?> neo4j = new Neo4jContainer<>(
		System.getProperty("neo4j-jdbc.default-neo4j-image"))
		.withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
		.waitingFor(Neo4jContainer.WAIT_FOR_BOLT)
		.withReuse(false);

	private String neo4jUrl;
	private String neo4jUser;
	private String neo4jPassword;

	@BeforeAll
	void configureLog() {
		System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tFT%1$tk:%1$tM:%1$tS.%1$tL%1$tz --- %5$s%n");

		var level = Level.INFO;
		var handler = new ConsoleHandler();
		handler.setLevel(level);
		JdbcImporterApp.LOGGER.setLevel(level);
		JdbcImporterApp.LOGGER.addHandler(handler);
		JdbcImporterApp.LOGGER.setUseParentHandlers(false);
	}

	@BeforeEach
	void startNeo4j() {

		var env = Dotenv.configure().ignoreIfMissing().ignoreIfMalformed().load();

		if (env.get("NEO4J_URI") == null) {
			JdbcImporterApp.LOGGER.log(Level.INFO, "Using Neo4j via docker");

			this.neo4j.start();
			this.neo4jUrl = "bolt://%s:%d".formatted(this.neo4j.getHost(), this.neo4j.getMappedPort(7687));
			this.neo4jUser = "neo4j";
			this.neo4jPassword = this.neo4j.getAdminPassword();
		} else {
			this.neo4jUrl = env.get("NEO4J_URI");
			this.neo4jUser = env.get("NEO4J_USERNAME");
			this.neo4jPassword = env.get("NEO4J_PASSWORD");

			JdbcImporterApp.LOGGER.log(Level.INFO, "Using Neo4j at " + this.neo4jUrl);

			try (
				var driver = GraphDatabase.driver(neo4jUrl, AuthTokens.basic(neo4jUser, neo4jPassword));
				var session = driver.session()
			) {
				session.run("""
						MATCH (n)
						CALL {
							WITH n DETACH DELETE n
						}
						IN TRANSACTIONS OF $rows ROWS"""
					, Map.of("rows", 10000)).consume();
			}
		}
	}

	@AfterEach
	void stopNeo4() {
		if (this.neo4j.isRunning()) {
			this.neo4j.stop();
		}
	}


	static Stream<Arguments> shouldLoadLDBCDataset() {
		return Stream.of(
			Arguments.of(5000, 1),
			Arguments.of(5000, 5),
			Arguments.of(20000, 1),
			Arguments.of(20000, 5),
			Arguments.of(50000, 1),
			Arguments.of(50000, 5),
			Arguments.of(75000, 1),
			Arguments.of(75000, 5)
		);
	}

	@ParameterizedTest
	@MethodSource
	void shouldLoadLDBCDataset(int batchSize, int maxConcurrentBatches) throws Exception {

		long start = System.nanoTime();
		var app = new JdbcImporterApp();
		app.model = Paths.get(Objects.requireNonNull(this.getClass().getResource("/ldbc/model.json")).toURI()).toFile();

		app.alwaysQuote = true;
		app.batchSize = batchSize;
		app.maxConcurrentBatches = maxConcurrentBatches;

		app.sourceUrl = "jdbc:postgresql://localhost:5432/ldbc";
		app.sourceUser = "postgres";
		app.sourcePassword = "mysecretpassword".toCharArray();

		app.targetUrl = URI.create(neo4jUrl);
		app.targetUser = neo4jUser;
		app.targetPassword = neo4jPassword.toCharArray();

		app.call();

		printStats(maxConcurrentBatches == 1 ? "Sequential" : "Parallel", batchSize, maxConcurrentBatches, start, System.nanoTime());
	}

	private void printStats(String type, int batchsize, int maxConcurrentBatches, long start, long end) {
		var duration = Duration.ofNanos(end - start);
		JdbcImporterApp.LOGGER.info(() -> "%s with a batch size of %d and %d concurrent batches took %02d:%02d:%02d".formatted(type, batchsize, maxConcurrentBatches, duration.toHours(), duration.toMinutesPart(),
			duration.toSecondsPart()));

		var sb = new StringBuilder("Stats");
		try (var driver = GraphDatabase.driver(neo4jUrl, AuthTokens.basic(neo4jUser, neo4jPassword));
		     var session = driver.session()) {

			var stats = new ArrayList<Stat>();
			stats.addAll(retrieveStats(session, """
				MATCH (n) RETURN labels(n)[0] AS label, count(*) AS cnt
				ORDER BY '(n:' + labels(n)[0] + ')'
				"""));
			stats.addAll(retrieveStats(session, """
				MATCH (n)-[r]->(m)
				WITH '(n:' + labels(n)[0] + ')-[' + type(r) + ']->(m:' +labels(m)[0] + ')' AS label
				RETURN label, count(*) AS cnt
				"""));
			var max = stats.stream().mapToInt(s -> s.label().length()).max().orElse(0);
			for (var stat : stats) {
				sb.append("\n")
					.append(String.format("%" + max + "s: %d", stat.label(), stat.num()));
			}

			JdbcImporterApp.LOGGER.info(sb.toString());
		}
	}

	record Stat(String label, long num) {
	}

	private static List<Stat> retrieveStats(Session session, String query) {
		var result = session.run(query);
		var stats = new ArrayList<Stat>();
		while (result.hasNext()) {
			var row = result.next();
			var stat = new Stat(row.get("label").asString(), row.get("cnt").asLong());
			stats.add(stat);
		}
		return stats;
	}
}
