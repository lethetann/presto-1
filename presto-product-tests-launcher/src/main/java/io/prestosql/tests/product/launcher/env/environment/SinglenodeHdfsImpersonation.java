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
import io.prestosql.tests.product.launcher.env.Environment;
import io.prestosql.tests.product.launcher.env.common.AbstractEnvironmentProvider;
import io.prestosql.tests.product.launcher.env.common.Hadoop;
import io.prestosql.tests.product.launcher.env.common.Standard;
import io.prestosql.tests.product.launcher.env.common.TestsEnvironment;

import javax.inject.Inject;

import static io.prestosql.tests.product.launcher.env.common.Hadoop.CONTAINER_PRESTO_HIVE_PROPERTIES;
import static io.prestosql.tests.product.launcher.env.common.Hadoop.CONTAINER_PRESTO_ICEBERG_PROPERTIES;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.utility.MountableFile.forHostPath;

@TestsEnvironment
public final class SinglenodeHdfsImpersonation
        extends AbstractEnvironmentProvider
{
    private final DockerFiles dockerFiles;

    @Inject
    public SinglenodeHdfsImpersonation(DockerFiles dockerFiles, Standard standard, Hadoop hadoop)
    {
        super(ImmutableList.of(standard, hadoop));
        this.dockerFiles = requireNonNull(dockerFiles, "dockerFiles is null");
    }

    @Override
    protected void extendEnvironment(Environment.Builder builder)
    {
        builder.configureContainer("presto-master", container -> container
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("conf/environment/singlenode-hdfs-impersonation/hive.properties")), CONTAINER_PRESTO_HIVE_PROPERTIES)
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("conf/environment/singlenode-hdfs-impersonation/iceberg.properties")), CONTAINER_PRESTO_ICEBERG_PROPERTIES));
    }
}
