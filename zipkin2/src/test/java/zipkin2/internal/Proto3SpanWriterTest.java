/**
 * Copyright 2015-2018 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.Test;
import zipkin2.Annotation;
import zipkin2.Endpoint;
import zipkin2.Span;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.atIndex;
import static zipkin2.TestObjects.CLIENT_SPAN;

public class Proto3SpanWriterTest {
  Buffer buf = new Buffer(2048); // bigger than needed to test sizeOf
  List<String> messages = new ArrayList<>();

  Logger logger = new Logger("", null) {
    {
      setLevel(Level.ALL);
    }

    @Override public void log(Level level, String msg) {
      assertThat(level).isEqualTo(Level.WARNING);
      messages.add(msg);
    }
  };

  Proto3SpanWriter writer = new Proto3SpanWriter(logger);

  @Test public void sizeOfStringField() {
    assertThat(writer.sizeOfStringField("12345678"))
      .isEqualTo(0
        + 1 /* tag of string field */ + 1 /* len */ + 8 // 12345678
      );
  }

  @Test public void sizeInBytes_warnsOnHugeName() {
    sizeWithHugeString(CLIENT_SPAN.name());

    assertThat(messages).containsExactly(
      "Span 7180c278b62e8f6a216a2aea45d08fc9/5b4185666d50f68b includes a huge name that will take 2097152 bytes to encode"
    );
  }

  @Test public void sizeInBytes_warnsOnHugeAnnotation() {
    sizeWithHugeString(CLIENT_SPAN.annotations().get(0).value());

    assertThat(messages).containsExactly(
      "Span 7180c278b62e8f6a216a2aea45d08fc9/5b4185666d50f68b includes a huge annotation that will take 2097166 bytes to encode"
    );
  }

  @Test public void sizeInBytes_warnsOnHugeTag() {
    sizeWithHugeString(CLIENT_SPAN.tags().values().iterator().next());

    assertThat(messages).containsExactly(
      "Span 7180c278b62e8f6a216a2aea45d08fc9/5b4185666d50f68b includes a huge tag that will take 2097179 bytes to encode"
    );
  }

  @Test public void sizeInBytes_warnsOnHugeLocalServiceName() {
    sizeWithHugeString(CLIENT_SPAN.localServiceName());

    assertThat(messages).containsExactly(
      "Span 7180c278b62e8f6a216a2aea45d08fc9/5b4185666d50f68b includes a huge local service name that will take 2097163 bytes to encode"
    );
  }

  @Test public void sizeInBytes_warnsOnHugeRemoteServiceName() {
    sizeWithHugeString(CLIENT_SPAN.remoteServiceName());

    assertThat(messages).containsExactly(
      "Span 7180c278b62e8f6a216a2aea45d08fc9/5b4185666d50f68b includes a huge remote service name that will take 2097166 bytes to encode"
    );
  }

  void sizeWithHugeString(String value) {
    writer = new Proto3SpanWriter(logger) {
      @Override int sizeOfStringField(String input) {
        if (value.equals(input)) return 2 * 1024 * 1024; // 2 meg
        return super.sizeOfStringField(input);
      }
    };
    writer.sizeInBytes(CLIENT_SPAN);
  }

  @Test public void sizeOfAnnotationField() {
    assertThat(writer.sizeOfAnnotationField(Annotation.create(1L, "12345678")))
      .isEqualTo(0
        + 1 /* tag of timestamp field */ + 8 /* 8 byte number */
        + 1 /* tag of value field */ + 1 /* len */ + 8 // 12345678
        + 1 /* tag of annotation field */ + 1 /* len */
      );
  }

  /** A map entry is an embedded messages: one for field the key and one for the value */
  @Test public void sizeOfMapEntryField() {
    assertThat(writer.sizeOfMapEntryField("123", "56789"))
      .isEqualTo(0
        + 1 /* tag of embedded key field */ + 1 /* len */ + 3
        + 1 /* tag of embedded value field  */ + 1 /* len */ + 5
        + 1 /* tag of map entry field */ + 1 /* len */
      );
  }

  @Test public void sizeOfEndpointField() {
    assertThat(writer.sizeOfEndpointField(Endpoint.newBuilder()
      .serviceName("12345678")
      .ip("192.168.99.101")
      .ip("2001:db8::c001")
      .port(80)
      .build()))
      .isEqualTo(0
        + 1 /* tag of servicename field */ + 1 /* len */ + 8 // 12345678
        + 1 /* tag of ipv4 field */ + 1 /* len */ + 4 // octets in ipv4
        + 1 /* tag of ipv6 field */ + 1 /* len */ + 16 // octets in ipv6
        + 1 /* tag of port field */ + 1 /* small varint */
        + 1 /* tag of endpoint field */ + 1 /* len */
      );
  }

  /** Shows we can reliably look at a byte zero to tell if we are decoding proto3 repeated fields. */
  @Test public void key_fieldOneLengthDelimited() {
    assertThat(Proto3SpanWriter.key(1, Proto3Codec.WIRETYPE_LENGTH_DELIMITED))
      .isEqualTo(0b00001010) // (field_number << 3) | wire_type = 1 << 3 | 2
      .isEqualTo(10); // for sanity of those looking at debugger, 4th bit + 2nd bit = 10
  }

  @Test public void write_startsWithTraceIdKey() {
    writer.write(CLIENT_SPAN, buf);

    assertThat(buf.toByteArray())
      .startsWith(10, 16); // field one is trace ID, with 16 byte length
  }

  @Test public void write_minimalSpan_writesIds() {
    writer.write(Span.newBuilder().traceId("1").id("2").build(), buf);
    assertThat(buf.toByteArray())
      .startsWith(
        0b00001010 /* trace ID key */, 8 /* bytes for 64-bit trace ID */,
        0, 0, 0, 0, 0, 0, 0, 1, // hex trace ID
        0b00011010 /* span ID key */, 8 /* bytes for 64-bit span ID */,
        0, 0, 0, 0, 0, 0, 0, 2 // hex span ID
      );
    assertThat(buf.pos)
      .isEqualTo(2 * 2 /* overhead of two fields */ + 2 * 8 /* 64-bit fields */)
      .isEqualTo(20); // easier math on the next test
  }

  @Test public void write_kind() {
    writer.write(Span.newBuilder().traceId("1").id("2").kind(Span.Kind.PRODUCER).build(), buf);
    assertThat(buf.toByteArray())
      .contains(0b0100000, atIndex(20)); // (field_number << 3) | wire_type = 4 << 3 | 0
  }

  @Test public void write_debug() {
    writer.write(CLIENT_SPAN.toBuilder().debug(true).build(), buf);

    assertThat(buf.toByteArray())
      .contains(0b01100000, atIndex(buf.pos - 2)) // (field_number << 3) | wire_type = 12 << 3 | 0
      .contains(1, atIndex(buf.pos - 1)); // true
  }

  @Test public void write_shared() {
    writer.write(CLIENT_SPAN.toBuilder().shared(true).build(), buf);

    assertThat(buf.toByteArray())
      .contains(0b01101000, atIndex(buf.pos - 2)) // (field_number << 3) | wire_type = 13 << 3 | 0
      .contains(1, atIndex(buf.pos - 1)); // true
  }

  @Test public void writeList_startsWithSpanKeyAndLengthPrefix() {
    byte[] buff = writer.writeList(asList(CLIENT_SPAN));

    assertThat(buff)
      .startsWith((byte) 10, writer.sizeInBytes(CLIENT_SPAN));
  }

  @Test public void writeList_empty() {
    assertThat(writer.writeList(asList()))
      .isSameAs(writer.EMPTY_ARRAY);
  }

  @Test public void writeList_offset_startsWithSpanKeyAndLengthPrefix() {
    writer.writeList(asList(CLIENT_SPAN), buf.toByteArray(), 0);

    assertThat(buf.toByteArray())
      .startsWith((byte) 10, writer.sizeInBytes(CLIENT_SPAN));
  }
}
