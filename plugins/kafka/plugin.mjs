#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const SCALARS = {
  string: 'String', boolean: 'Boolean', integer: 'java.math.BigInteger', number: 'java.math.BigDecimal',
};
const RESERVED = new Set(('abstract assert boolean break byte case catch char class const continue default do double else enum '
  + 'extends final finally float for goto if implements import instanceof int interface long native new package private '
  + 'protected public return short static strictfp super switch synchronized this throw throws transient try void volatile '
  + 'while true false null clone finalize getClass hashCode notify notifyAll toString wait _').split(' '));

function identifier(value) {
  return typeof value === 'string' && /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(value) && !RESERVED.has(value);
}

function docText(description) {
  if (typeof description !== 'string' || !description.trim()) return [];
  return description.trim()
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('\\', '&#92;').replaceAll('*/', '*&#47;').replaceAll('@', '&#64;')
    .split(/\r\n|\r|\n/).map(line => line.trimEnd());
}

function javadoc(lines) {
  if (!lines.length) return '';
  return '    /**\n' + lines.map(line => line ? '     * ' + line + '\n' : '     *\n').join('') + '     */\n';
}

function render(connection, method, className) {
  const event = method.event_operation;
  const settings = connection.connection_settings;
  if (method.pattern !== 'PUB_SUB' || !event || !['SEND', 'RECEIVE'].includes(event.direction)
      || typeof event.topic !== 'string' || !/^[A-Za-z0-9._-]{1,249}$/.test(event.topic) || ['.', '..'].includes(event.topic)) {
    throw new Error('A Kafka connection needs imported AsyncAPI event metadata');
  }
  if (connection.mapping_rules || (settings.security_type ?? 'NONE') !== 'NONE'
      || typeof settings.host !== 'string' || !/^[A-Za-z0-9.-]+$/.test(settings.host)
      || !Number.isInteger(settings.port) || settings.port < 1 || settings.port > 65535) {
    throw new Error('The Kafka pilot requires plaintext broker settings and an unmapped payload');
  }
  const schema = method.request_schema;
  const properties = Object.entries(schema?.properties ?? {}).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0);
  if (schema?.type !== 'object' || !properties.length || properties.some(([name, property]) =>
    !identifier(name) || !Object.hasOwn(SCALARS, property?.type))) {
    throw new Error('The Kafka pilot requires a flat JSON payload with scalar properties');
  }
  const payload = properties.map(([name, property]) => SCALARS[property.type] + ' ' + name).join(', ');
  const componentDocs = properties.flatMap(([name, property]) => {
    const lines = docText(property.description);
    if (lines.length) lines[0] = '@param ' + name + ' ' + lines[0];
    return lines;
  });
  const payloadDocs = docText(schema.description).concat(componentDocs);
  const javaImports = event.direction === 'SEND'
    ? 'import org.apache.kafka.clients.producer.*;'
    : 'import org.apache.kafka.clients.consumer.*;\nimport java.time.Duration;\nimport java.util.List;\nimport java.util.Optional;';
  const body = event.direction === 'SEND'
    ? `public RecordMetadata ${method.method_name}(Payload payload) throws Exception {
        Properties settings = new Properties();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        try (var producer = new KafkaProducer<String, String>(settings, new StringSerializer(), new StringSerializer())) {
            return producer.send(new ProducerRecord<>(TOPIC, mapper.writeValueAsString(payload))).get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }`
    : `public Optional<Payload> ${method.method_name}(String groupId, Duration timeout) throws Exception {
        Properties settings = new Properties();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        settings.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        try (var consumer = new KafkaConsumer<String, String>(settings, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            long started = System.nanoTime();
            long limit = timeout.toNanos();
            for (long remaining = limit; remaining > 0; remaining = limit - (System.nanoTime() - started)) {
                for (var record : consumer.poll(Duration.ofNanos(Math.min(remaining, 500_000_000)))) {
                    var payload = mapper.readValue(record.value(), Payload.class);
                    consumer.commitSync();
                    return Optional.of(payload);
                }
            }
            return Optional.empty();
        }
    }`;

  return `package sddp.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Properties;
import org.apache.kafka.common.serialization.*;
${javaImports}

public final class ${className} {
    private static final String TOPIC = "${event.topic}";
    private static final String BOOTSTRAP = "${settings.host}:${settings.port}";
    private final ObjectMapper mapper = new ObjectMapper();

${javadoc(payloadDocs)}    public record Payload(${payload}) { }

${javadoc(docText(method.description))}    ${body}
}
`;
}

async function generate(input, output) {
  const manifest = JSON.parse(await readFile(input, 'utf8'));
  const files = new Map();
  for (const service of manifest.services) {
    for (const connection of service.outbound_connections) {
      if (connection.transport_type !== 'KAFKA') continue;
      for (const method of connection.methods) {
        if (!identifier(method.method_name)) throw new Error('Invalid Kafka operation identifier');
        const className = 'Kafka_' + method.method_name;
        const file = className + '.java';
        if (files.has(file)) throw new Error('Kafka operation names must be unique in the generated package');
        files.set(file, render(connection, method, className));
      }
    }
  }
  if (!files.size) throw new Error('The IR contains no Kafka connections');
  for (const [file, source] of files) await writeFile(join(output, file), source, 'utf8');
}

try {
  await generate(process.argv[2], process.argv[3]);
} catch (error) {
  console.error('Kafka generation failed: ' + error.message);
  process.exitCode = 1;
}
