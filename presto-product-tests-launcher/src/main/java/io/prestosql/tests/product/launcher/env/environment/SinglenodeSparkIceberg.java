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
package io.prestosql.tests.product.launcher.env.environment;

import com.google.common.collect.ImmutableList;
import io.prestosql.tests.product.launcher.docker.DockerFiles;
import io.prestosql.tests.product.launcher.env.DockerContainer;
import io.prestosql.tests.product.launcher.env.Environment;
import io.prestosql.tests.product.launcher.env.EnvironmentOptions;
import io.prestosql.tests.product.launcher.env.common.AbstractEnvironmentProvider;
import io.prestosql.tests.product.launcher.env.common.Hadoop;
import io.prestosql.tests.product.launcher.env.common.Standard;
import io.prestosql.tests.product.launcher.env.common.TestsEnvironment;
import io.prestosql.tests.product.launcher.testcontainers.PortBinder;
import io.prestosql.tests.product.launcher.testcontainers.SelectedPortWaitStrategy;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;

import javax.inject.Inject;

import static io.prestosql.tests.product.launcher.env.common.Standard.CONTAINER_PRESTO_ETC;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.utility.MountableFile.forHostPath;

@TestsEnvironment
public class SinglenodeSparkIceberg
        extends AbstractEnvironmentProvider
{
    private static final int SPARK_THRIFT_PORT = 10213;

    private final DockerFiles dockerFiles;
    private final PortBinder portBinder;
    private final String hadoopImagesVersion;

    @Inject
    public SinglenodeSparkIceberg(Standard standard, Hadoop hadoop, DockerFiles dockerFiles, EnvironmentOptions environmentOptions, PortBinder portBinder)
    {
        super(ImmutableList.of(standard, hadoop));
        this.dockerFiles = requireNonNull(dockerFiles, "dockerFiles is null");
        this.portBinder = requireNonNull(portBinder, "portBinder is null");
        this.hadoopImagesVersion = requireNonNull(environmentOptions.hadoopImagesVersion, "environmentOptions.hadoopImagesVersion is null");
    }

    @Override
    protected void extendEnvironment(Environment.Builder builder)
    {
        builder.configureContainer("hadoop-master", dockerContainer -> {
            dockerContainer.setDockerImageName("prestodev/hdp3.1-hive:" + hadoopImagesVersion);
            dockerContainer.withCreateContainerCmdModifier(createContainerCmd ->
                    createContainerCmd.withEntrypoint(ImmutableList.of(("/docker/presto-product-tests/conf/environment/singlenode-hdp3/hadoop-entrypoint.sh"))));
        });

        builder.configureContainer("presto-master", container -> container
                .withCopyFileToContainer(
                        forHostPath(dockerFiles.getDockerFilesHostPath("conf/environment/singlenode-spark-iceberg/iceberg.properties")),
                        CONTAINER_PRESTO_ETC + "/catalog/iceberg.properties"));

        DockerContainer spark = createSpark();
        // Spark needs the HMS to be up before it starts
        builder.configureContainer("hadoop-master", spark::dependsOn);

        builder.addContainer("spark", spark);
    }

    @SuppressWarnings("resource")
    private DockerContainer createSpark()
    {
        DockerContainer container = new DockerContainer("prestodev/spark3.0-iceberg:" + hadoopImagesVersion)
                .withEnv("HADOOP_USER_NAME", "hive")
                .withCopyFileToContainer(
                        forHostPath(dockerFiles.getDockerFilesHostPath("conf/environment/singlenode-spark-iceberg/spark-defaults.conf")),
                        "/spark/conf/spark-defaults.conf")
                .withCommand(
                        "spark-submit",
                        "--master", "local[*]",
                        "--class", "org.apache.spark.sql.hive.thriftserver.HiveThriftServer2",
                        "--name", "Thrift JDBC/ODBC Server",
                        "--conf", "spark.hive.server2.thrift.port=" + SPARK_THRIFT_PORT,
                        "spark-internal")
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .waitingFor(new SelectedPortWaitStrategy(SPARK_THRIFT_PORT));

        portBinder.exposePort(container, SPARK_THRIFT_PORT);

        return container;
    }
}
