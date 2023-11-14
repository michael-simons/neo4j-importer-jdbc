package org.neo4j.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.assertj.core.data.Index;
import org.junit.jupiter.api.Test;

class MetadataTest {

	@Test
	void metaDataShouldWork() throws URISyntaxException, SQLException {

		var app = new JdbcImporterApp();
		var testDb = this.getClass().getResource("/states/test.db");
		app.sourceUrl = "jdbc:duckdb:" + Paths.get(testDb.toURI());
		app.sourceSchema = "main";

		var metaData = app.getSourceMetadata()
			.stream().collect(Collectors.toMap(JdbcImporterApp.Table::name, Function.identity()));
		assertThat(metaData).hasSize(2);

		var states = metaData.get("states");
		assertThat(states.columns()).hasSize(4);
		assertThat(states.columns().stream().sorted(Comparator.comparing(JdbcImporterApp.Column::name)))
			.satisfies(c -> {
				assertThat(c.name()).isEqualTo("latitude");
				assertThat(c.isNullable()).isTrue();
				assertThat(c.isPrimaryKey()).isFalse();
			}, Index.atIndex(0))
			.satisfies(c -> {
				assertThat(c.name()).isEqualTo("longitude");
				assertThat(c.isNullable()).isTrue();
				assertThat(c.isPrimaryKey()).isFalse();
			}, Index.atIndex(1))
			.satisfies(c -> {
				assertThat(c.name()).isEqualTo("name");
				assertThat(c.isNullable()).isTrue();
				assertThat(c.isPrimaryKey()).isFalse();
			}, Index.atIndex(2))
			.satisfies(c -> {
				assertThat(c.name()).isEqualTo("state");
				assertThat(c.isNullable()).isFalse();
				assertThat(c.isPrimaryKey()).isTrue();
			}, Index.atIndex(3));
	}
}
