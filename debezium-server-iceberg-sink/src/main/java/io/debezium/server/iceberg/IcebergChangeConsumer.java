/*
 *
 *  * Copyright memiiso Authors.
 *  *
 *  * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package io.debezium.server.iceberg;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import io.debezium.serde.DebeziumSerdes;
import io.debezium.server.BaseChangeConsumer;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.enterprise.context.Dependent;
import javax.inject.Named;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.*;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the consumer that delivers the messages into Amazon S3 destination.
 *
 * @author Ismail Simsek
 */
@Named("iceberg")
@Dependent
public class IcebergChangeConsumer extends BaseChangeConsumer implements DebeziumEngine.ChangeConsumer<ChangeEvent<Object, Object>> {

  private static final Logger LOGGER = LoggerFactory.getLogger(IcebergChangeConsumer.class);
  private static final String PROP_PREFIX = "debezium.sink.iceberg.";
  @ConfigProperty(name = "debezium.format.value", defaultValue = "json")
  String valueFormat;
  @ConfigProperty(name = "debezium.format.key", defaultValue = "json")
  String keyFormat;
  Configuration hadoopConf = new Configuration();
  @ConfigProperty(name = PROP_PREFIX + "catalog-impl" /* CatalogProperties.CATALOG_IMPL */, defaultValue = "hadoop")
  String catalogImpl;
  @ConfigProperty(name = PROP_PREFIX + "warehouse" /* CatalogProperties.WAREHOUSE_LOCATION */)
  String warehouseLocation;
  @ConfigProperty(name = PROP_PREFIX + "fs.defaultFS")
  String defaultFs;
  @ConfigProperty(name = PROP_PREFIX + "table-prefix", defaultValue = "")
  String tablePrefix;
  @ConfigProperty(name = "debezium.format.value.schemas.enable", defaultValue = "false")
  boolean eventSchemaEnabled;

  Catalog icebergCatalog;
  Serde<JsonNode> valSerde = DebeziumSerdes.payloadJson(JsonNode.class);
  Deserializer<JsonNode> valDeserializer;

  @PostConstruct
  void connect() throws InterruptedException {
    if (!valueFormat.equalsIgnoreCase(Json.class.getSimpleName().toLowerCase())) {
      throw new InterruptedException("debezium.format.value={" + valueFormat + "} not supported! Supported (debezium.format.value=*) formats are {json,}!");
    }
    if (!keyFormat.equalsIgnoreCase(Json.class.getSimpleName().toLowerCase())) {
      throw new InterruptedException("debezium.format.key={" + valueFormat + "} not supported! Supported (debezium.format.key=*) formats are {json,}!");
    }

    // loop and set hadoopConf
    for (String name : ConfigProvider.getConfig().getPropertyNames()) {
      if (name.startsWith(PROP_PREFIX)) {
        this.hadoopConf.set(name.substring(PROP_PREFIX.length()), ConfigProvider.getConfig().getValue(name, String.class));
        LOGGER.debug("Setting Hadoop Conf '{}' from application.properties!", name.substring(PROP_PREFIX.length()));
      }
    }

    if (warehouseLocation == null || warehouseLocation.trim().isEmpty()) {
      warehouseLocation = defaultFs + "/iceberg_warehouse";
    }

    icebergCatalog = new HadoopCatalog("iceberg", hadoopConf, warehouseLocation);
    // @TODO iceberg 11 . make catalog dynamic using catalogImpl parametter!
    // if (catalogImpl != null) {
    // icebergCatalog = CatalogUtil.loadCatalog(catalogImpl, name, options, hadoopConf);
    // }
    valSerde.configure(Collections.emptyMap(), false);
    valDeserializer = valSerde.deserializer();
  }

  public String map(String destination) {
    return destination.replace(".", "-");
  }

  private Table createIcebergTable(TableIdentifier tableIdentifier, ChangeEvent<Object, Object> event) {

    if (!eventSchemaEnabled) {
      return null;
    }

    try {
      // Table not exists, try to create it using the schema of an event
      JsonNode jsonSchema = new ObjectMapper().readTree(getBytes(event.value()));

      if (IcebergUtil.hasSchema(jsonSchema)) {
        Schema schema = IcebergUtil.getIcebergSchema(jsonSchema.get("schema"));
        LOGGER.warn("Creating table '{}'\nWith schema:\n{}", tableIdentifier, schema.toString());
        // @TODO use schema of key event to create primary key definition! for upsert feature
        return icebergCatalog.createTable(tableIdentifier, schema);
      }

    } catch (Exception ignored) {
    }
    return null;
  }

  @Override
  public void handleBatch(List<ChangeEvent<Object, Object>> records, DebeziumEngine.RecordCommitter<ChangeEvent<Object, Object>> committer)
      throws InterruptedException {

    Map<String, ArrayList<ChangeEvent<Object, Object>>> result = records.stream()
        .collect(Collectors.groupingBy(
            objectObjectChangeEvent -> map(objectObjectChangeEvent.destination()),
            Collectors.mapping(p -> p,
                Collectors.toCollection(ArrayList::new))));

    for (Map.Entry<String, ArrayList<ChangeEvent<Object, Object>>> event : result.entrySet()) {
      Table icebergTable;
      final Schema tableSchema;
      final TableIdentifier tableIdentifier = TableIdentifier.of(tablePrefix + event.getKey());
      try {
        // load iceberg table
        icebergTable = icebergCatalog.loadTable(tableIdentifier);
      } catch (org.apache.iceberg.exceptions.NoSuchTableException e) {
        // get schema fom an event and create iceberg table
        icebergTable = createIcebergTable(tableIdentifier, event.getValue().get(0));
        if (icebergTable == null) {
          LOGGER.warn("Iceberg table '{}' not found! Ignoring received data for the table} !", tableIdentifier);
          continue;
        }
      }
      tableSchema = icebergTable.schema();
      ArrayList<Record> icebergRecords = new ArrayList<>();
      for (ChangeEvent<Object, Object> e : event.getValue()) {
        GenericRecord icebergRecord = IcebergUtil.getIcebergRecord(tableSchema, valDeserializer.deserialize(e.destination(),
            getBytes(e.value())));
        icebergRecords.add(icebergRecord);
        //committer.markProcessed(e); don't call events are shuffled!
      }

      appendTable(icebergTable, icebergRecords);
    }
    committer.markBatchFinished();
  }

  private void appendTable(Table icebergTable, ArrayList<Record> icebergRecords) throws InterruptedException {
    final String fileName = UUID.randomUUID() + "-" + Instant.now().toEpochMilli() + "." + FileFormat.PARQUET.toString().toLowerCase();
    OutputFile out = icebergTable.io().newOutputFile(icebergTable.locationProvider().newDataLocation(fileName));

    FileAppender<Record> writer;
    try {
      LOGGER.debug("Writing data to file: {}!", out);
      //BaseEqualityDeltaWriter.write
      //BaseEqualityDeltaWriter.deleteKey
      writer = Parquet.write(out)
          .createWriterFunc(GenericParquetWriter::buildWriter)
          .forTable(icebergTable)
          .overwrite()
          .build();

      try (Closeable toClose = writer) {
        writer.addAll(icebergRecords);
      }
    } catch (IOException e) {
      throw new InterruptedException(e.getMessage());
    }

    LOGGER.debug("Building DataFile!");
    DataFile dataFile = DataFiles.builder(icebergTable.spec())
        .withFormat(FileFormat.PARQUET)
        .withPath(out.location())
        .withFileSizeInBytes(writer.length())
        .withSplitOffsets(writer.splitOffsets())
        .withMetrics(writer.metrics())
        .build();

    LOGGER.debug("Committing new file as newAppend '{}' !", dataFile.path());
    icebergTable.newAppend()
        .appendFile(dataFile)
        .commit();
    LOGGER.info("Committed events to table! {}", icebergTable.location());
  }

}