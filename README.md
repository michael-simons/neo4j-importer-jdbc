# A naive JDBC importer for Neo4j

* Uses the model files from the web UI (Importer UI)
* Pretends the files in there are tables in the source database
* Imports everything as configured in the model, no support for excluded ids atm

## How to run

This uses the test data itself, for your relational database replace the DuckDB driver with something for your relational database.

Bring up a Neo4j first, i.e. via Docker

```bash
docker run --publish=7474:7474 --publish=7687:7687 \
  -e 'NEO4J_AUTH=neo4j/verysecret' \
  -e 'NEO4J_ACCEPT_LICENSE_AGREEMENT=yes' \
  -d  neo4j:5.12-enterprise
```

```
./mvnw -DskipTests clean package
CLASSPATH_PREFIX=~/.m2/repository/org/duckdb/duckdb_jdbc/0.9.1/duckdb_jdbc-0.9.1.jar \
  target/assembly/bin/jdbc-importer-app \
  --source jdbc:duckdb:`pwd`/src/test/resources/states/test.db \
  --address neo4j://localhost:7687 \
  --username neo4j \
  --password verysecret \
  ./src/test/resources/states/model.json       
```
