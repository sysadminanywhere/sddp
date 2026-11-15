package ru.sddp.platform.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.common.utils.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class KafkaCompressionCompatibilityTest {
    @Test void kafkaLz4AndSnappyRoundTrip() throws Exception {
        byte[] input = "Kafka compression compatibility ".repeat(1000).getBytes(StandardCharsets.UTF_8);
        for (Compression compression : List.of(Compression.lz4().build(), Compression.snappy().build())) {
            var output = new ByteBufferOutputStream(1024);
            try (var compressed = compression.wrapForOutput(output, RecordBatch.CURRENT_MAGIC_VALUE)) {
                compressed.write(input);
            }
            var buffer = output.buffer();
            buffer.flip();
            try (var decompressed = compression.wrapForInput(buffer, RecordBatch.CURRENT_MAGIC_VALUE, BufferSupplier.NO_CACHING)) {
                assertArrayEquals(input, decompressed.readAllBytes(), compression.type().name);
            }
        }
    }
}
