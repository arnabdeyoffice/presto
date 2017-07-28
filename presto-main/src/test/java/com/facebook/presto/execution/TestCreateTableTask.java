
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
package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.connector.ConnectorId;
import com.facebook.presto.metadata.AbstractMockMetadata;
import com.facebook.presto.metadata.Catalog;
import com.facebook.presto.metadata.CatalogManager;
import com.facebook.presto.metadata.QualifiedObjectName;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.metadata.TablePropertyManager;
import com.facebook.presto.security.AllowAllAccessControl;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.sql.tree.ColumnDefinition;
import com.facebook.presto.sql.tree.CreateTable;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.transaction.TransactionManager;
import com.facebook.presto.type.TypeRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.facebook.presto.spi.StandardErrorCode.ALREADY_EXISTS;
import static com.facebook.presto.spi.session.PropertyMetadata.stringSessionProperty;
import static com.facebook.presto.testing.TestingSession.createBogusTestingCatalog;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static com.facebook.presto.transaction.TransactionManager.createTestTransactionManager;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestCreateTableTask
{
    private static final String CATALOG_NAME = "catalog";
    private CatalogManager catalogManager;
    private TypeManager typeManager;
    private TransactionManager transactionManager;
    private TablePropertyManager tablePropertyManager;
    private Catalog testCatalog;
    private Session testSession;
    private MockMetadata metadata;

    @BeforeMethod
    public void setUp()
            throws Exception
    {
        catalogManager = new CatalogManager();
        typeManager = new TypeRegistry();
        transactionManager = createTestTransactionManager(catalogManager);
        tablePropertyManager = new TablePropertyManager();
        testCatalog = createBogusTestingCatalog(CATALOG_NAME);
        catalogManager.registerCatalog(testCatalog);
        tablePropertyManager.addProperties(testCatalog.getConnectorId(),
                ImmutableList.of(stringSessionProperty("baz", "test property", null, false)));
        testSession = testSessionBuilder()
                .setTransactionId(transactionManager.beginTransaction(false))
                .build();
        metadata = new MockMetadata(typeManager,
                tablePropertyManager,
                testCatalog.getConnectorId());
    }

    @Test
    public void testCreateTableNotExistsTrue()
            throws Exception
    {
        CreateTable statement = new CreateTable(QualifiedName.of("test_table"),
                ImmutableList.of(new ColumnDefinition("a", "BIGINT", Optional.empty())),
                true,
                ImmutableMap.of(),
                Optional.empty());

        getFutureValue(new CreateTableTask().internalExecute(statement, metadata, new AllowAllAccessControl(), testSession, emptyList()));
        assertEquals(metadata.getCreateTableCallCount(), 1);
    }

    @Test
    public void testCreateTableNotExistsFalse()
            throws Exception
    {
        CreateTable statement = new CreateTable(QualifiedName.of("test_table"),
                ImmutableList.of(new ColumnDefinition("a", "BIGINT", Optional.empty())),
                false,
                ImmutableMap.of(),
                Optional.empty());

        try {
            getFutureValue(new CreateTableTask().internalExecute(statement, metadata, new AllowAllAccessControl(), testSession, emptyList()));
            fail("expected exception");
        }
        catch (RuntimeException e) {
            // Expected
            assertTrue(e instanceof PrestoException);
            PrestoException prestoException = (PrestoException) e;
            assertTrue(prestoException.getErrorCode().equals(ALREADY_EXISTS.toErrorCode()));
        }
        assertEquals(metadata.getCreateTableCallCount(), 1);
    }

    private static class MockMetadata
            extends AbstractMockMetadata
    {
        private final TypeManager typeManager;
        private final TablePropertyManager tablePropertyManager;
        private final ConnectorId catalogHandle;
        private AtomicInteger createTableCallCount = new AtomicInteger();

        public MockMetadata(
                TypeManager typeManager,
                TablePropertyManager tablePropertyManager,
                ConnectorId catalogHandle)
        {
            this.typeManager = requireNonNull(typeManager, "typeManager is null");
            this.tablePropertyManager = requireNonNull(tablePropertyManager, "tablePropertyManager is null");
            this.catalogHandle = requireNonNull(catalogHandle, "catalogHandle is null");
        }

        @Override
        public void createTable(Session session, String catalogName, ConnectorTableMetadata tableMetadata)
        {
            createTableCallCount.incrementAndGet();
            throw new PrestoException(ALREADY_EXISTS, "Table already exists");
        }

        @Override
        public TablePropertyManager getTablePropertyManager()
        {
            return tablePropertyManager;
        }

        @Override
        public Type getType(TypeSignature signature)
        {
            return typeManager.getType(signature);
        }

        @Override
        public Optional<ConnectorId> getCatalogHandle(Session session, String catalogName)
        {
            if (catalogHandle.getCatalogName().equals(catalogName)) {
                return Optional.of(catalogHandle);
            }
            return Optional.empty();
        }

        @Override
        public Optional<TableHandle> getTableHandle(Session session, QualifiedObjectName tableName)
        {
            return Optional.empty();
        }

        public int getCreateTableCallCount()
        {
            return createTableCallCount.get();
        }

        @Override
        public void dropColumn(Session session, TableHandle tableHandle, ColumnHandle column)
        {
            throw new UnsupportedOperationException();
        }
    }
}
