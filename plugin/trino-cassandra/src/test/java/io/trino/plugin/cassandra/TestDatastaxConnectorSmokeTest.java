/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.cassandra;

import io.trino.testing.QueryRunner;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.util.Map;

import static io.trino.plugin.cassandra.CassandraTestingUtils.createTestTables;

public class TestDatastaxConnectorSmokeTest
        extends BaseCassandraConnectorSmokeTest
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        CassandraServer server = closeAfterClass(new CassandraServer(
                DockerImageName.parse("datastax/dse-server:6.8.47").asCompatibleSubstituteFor("cassandra"),
                Map.of(
                        "DS_LICENSE", "accept",
                        "DC", "datacenter1"),
                "/config/cassandra.yaml",
                "cassandra-dse.yaml"));
        CassandraSession session = server.getSession();
        createTestTables(session, KEYSPACE, Timestamp.from(TIMESTAMP_VALUE.toInstant()));
        return CassandraQueryRunner.builder(server)
                .setInitialTables(REQUIRED_TPCH_TABLES)
                .build();
    }
}
