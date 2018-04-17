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

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import zipkin2.Annotation;
import zipkin2.Endpoint;
import zipkin2.Span;

import static zipkin2.internal.Proto3Codec.STRING_LENGTH_LIMIT;
import static zipkin2.internal.Proto3Codec.WIRETYPE_FIXED64;
import static zipkin2.internal.Proto3Codec.WIRETYPE_LENGTH_DELIMITED;
import static zipkin2.internal.Proto3Codec.WIRETYPE_VARINT;

/**
 * Everything here assumes the field numbers are less than 16, implying a 1 byte tag.
 */
//@Immutable
class Proto3SpanWriter implements Buffer.Writer<Span> { // not final for tests
  final Logger log;

  Proto3SpanWriter() {
    log = Logger.getLogger(getClass().getName());
  }

  Proto3SpanWriter(Logger log) { // for testing
    this.log = log;
  }

  /** Don't write empty endpoints */
  static final Endpoint EMPTY_ENDPOINT = Endpoint.newBuilder().build();
  static final byte[] EMPTY_ARRAY = new byte[0];

  @Override public int sizeInBytes(Span span) {
    int sizeInBytes = 2 + span.traceId().length() / 2; // tag + len + 8 or 16 bytes

    if (span.parentId() != null) sizeInBytes += 10; // tag + len + 8 bytes

    sizeInBytes += 10; // id = tag + len + 8 bytes

    if (span.kind() != null) sizeInBytes += 2; // tag + byte

    String name = span.name();
    if (name != null) {
      int sizeOfName = sizeOfStringField(name);
      warnIfGreaterThan(sizeOfName, STRING_LENGTH_LIMIT, span, "name");
      sizeInBytes += sizeOfName;
    }

    if (span.timestampAsLong() != 0L) sizeInBytes += 9; // tag + 8 byte number

    long duration = span.durationAsLong();
    if (duration != 0L) sizeInBytes += 1 + Buffer.varintSizeInBytes(duration); // tag + varint

    int sizeOfLocalEndpointField = sizeOfEndpointField(span.localEndpoint());
    warnIfGreaterThan(sizeOfLocalEndpointField, STRING_LENGTH_LIMIT, span, "local service name");
    sizeInBytes += sizeOfLocalEndpointField;

    int sizeOfRemoteEndpointField = sizeOfEndpointField(span.remoteEndpoint());
    warnIfGreaterThan(sizeOfRemoteEndpointField, STRING_LENGTH_LIMIT, span, "remote service name");
    sizeInBytes += sizeOfRemoteEndpointField;

    List<Annotation> annotations = span.annotations();
    int annotationCount = annotations.size();
    for (int i = 0; i < annotationCount; i++) {
      int sizeOfAnnotation = sizeOfAnnotationField(annotations.get(i));
      warnIfGreaterThan(sizeOfAnnotation, STRING_LENGTH_LIMIT, span, "annotation");
      sizeInBytes += sizeOfAnnotation;
    }

    Map<String, String> tags = span.tags();
    int tagCount = tags.size();
    if (tagCount > 0) { // avoid allocating an iterator when empty
      for (Map.Entry<String, String> entry : tags.entrySet()) {
        int sizeOfMapEntry = sizeOfMapEntryField(entry.getKey(), entry.getValue());
        warnIfGreaterThan(sizeOfMapEntry, STRING_LENGTH_LIMIT, span, "tag");
        sizeInBytes += sizeOfMapEntry;
      }
    }

    if (Boolean.TRUE.equals(span.debug())) sizeInBytes += 2; // tag + byte
    if (Boolean.TRUE.equals(span.shared())) sizeInBytes += 2; // tag + byte

    return sizeInBytes;
  }

  void warnIfGreaterThan(int size, int limit, Span value, String context) {
    if (size > limit && log.isLoggable(Level.WARNING)) {
      log.warning("Span " + value.traceId() + "/" + value.id()
        + " includes a huge " + context + " that will take " + size + " bytes to encode");
    }
  }

  /**
   * "Each key in the streamed message is a varint with the value {@code (field_number << 3) | wire_type}"
   *
   * <p>See https://developers.google.com/protocol-buffers/docs/encoding#structure
   */
  static int key(int fieldNumber, int wireType) {
    return (fieldNumber << 3) | wireType;
  }

  @Override public void write(Span value, Buffer b) {
    decodeLowerHexField(1, value.traceId(), b);

    String parentId = value.parentId();
    if (parentId != null) decodeLowerHexField(2, parentId, b);

    decodeLowerHexField(3, value.id(), b);

    Span.Kind kind = value.kind();
    if (kind != null) {
      b.writeByte(key(4, WIRETYPE_VARINT));
      b.writeByte(kind.ordinal() + 1); // in java, there's no index for unknown
    }

    writeUtf8Field(5, value.name(), b);

    long timestamp = value.timestampAsLong();
    if (timestamp != 0L) {
      b.writeByte(key(6, WIRETYPE_FIXED64));
      b.writeLongLe(timestamp);
    }

    long duration = value.durationAsLong();
    if (duration != 0L) {
      b.writeByte(key(7, WIRETYPE_VARINT));
      b.writeVarint(duration);
    }

    writeEndpointField(8, value.localEndpoint(), b);
    writeEndpointField(9, value.remoteEndpoint(), b);

    List<Annotation> annotations = value.annotations();
    int annotationLength = annotations.size();
    for (int i = 0; i < annotationLength; i++) {
      writeAnnotationField(10, annotations.get(i), b);
    }

    Map<String, String> tags = value.tags();
    if (!tags.isEmpty()) { // avoid allocating an iterator when empty
      for (Map.Entry<String, String> entry : tags.entrySet()) {
        writeMapEntryField(11, entry.getKey(), entry.getValue(), b);
      }
    }

    if (Boolean.TRUE.equals(value.debug())) {
      b.writeByte(key(12, WIRETYPE_VARINT)).writeByte(1);
    }

    if (Boolean.TRUE.equals(value.shared())) {
      b.writeByte(key(13, WIRETYPE_VARINT)).writeByte(1);
    }
  }

  static void writeUtf8Field(int fieldNumber, @Nullable String utf8, Buffer b) {
    if (utf8 == null) return;
    b.writeByte(key(fieldNumber, WIRETYPE_LENGTH_DELIMITED));
    b.writeVarint(Buffer.utf8SizeInBytes(utf8)); // length prefix
    b.writeUtf8(utf8);
  }

  static void writeBytesField(int fieldNumber, @Nullable byte[] bytes, Buffer b) {
    if (bytes == null) return;
    b.writeByte(key(fieldNumber, WIRETYPE_LENGTH_DELIMITED));
    b.writeVarint(bytes.length); // length prefix
    b.write(bytes);
  }

  static void decodeLowerHexField(int fieldNumber, String hex, Buffer b) {
    b.writeByte(key(fieldNumber, WIRETYPE_LENGTH_DELIMITED));
    int hexLength = hex.length();
    b.writeByte(hexLength / 2); // length prefix
    // similar logic to okio.ByteString.decodeHex
    for (int i = 0; i < hexLength; i++) {
      int d1 = decodeLowerHex(hex.charAt(i++)) << 4;
      int d2 = decodeLowerHex(hex.charAt(i));
      b.writeByte((byte) (d1 + d2));
    }
  }

  static int decodeLowerHex(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    throw new AssertionError("not lowerHex " + c); // bug
  }

  @Override public String toString() {
    return "Span";
  }

  int sizeOfEndpointField(@Nullable Endpoint value) {
    if (value == null || EMPTY_ENDPOINT.equals(value)) return 0;
    int sizeOfEndpoint = sizeOfEndpoint(value);
    return sizeOfLengthDelimitedField(sizeOfEndpoint);
  }

  int sizeOfEndpoint(@Nullable Endpoint value) {
    int result = 0;
    if (value.serviceName() != null) result += sizeOfStringField(value.serviceName());
    if (value.ipv4() != null) result += 6; // tag + len + 4 bytes
    if (value.ipv6() != null) result += 18; // tag + len + 16
    int port = value.portAsInt();
    if (port != 0) result += 1 + Buffer.varintSizeInBytes(port); // tag + varint
    return result;
  }

  void writeEndpointField(int fieldNumber, @Nullable Endpoint value, Buffer b) {
    if (value == null || EMPTY_ENDPOINT.equals(value)) return;

    b.writeByte(key(fieldNumber, WIRETYPE_LENGTH_DELIMITED));
    b.writeVarint(sizeOfEndpoint(value)); // length prefix

    writeUtf8Field(1, value.serviceName(), b);
    writeBytesField(2, value.ipv4Bytes(), b);
    writeBytesField(3, value.ipv6Bytes(), b);
    int port = value.portAsInt();
    if (port != 0) {
      b.writeByte(key(4, WIRETYPE_VARINT));
      b.writeVarint(port);
    }
  }

  int sizeOfAnnotationField(Annotation value) {
    int sizeOfAnnotation = sizeOfAnnotation(value);
    return sizeOfLengthDelimitedField(sizeOfAnnotation);
  }

  int sizeOfAnnotation(Annotation value) {
    return 9 /* tag + 8 byte number */ + sizeOfStringField(value.value());
  }

  void writeAnnotationField(int fieldNumber, Annotation value, Buffer b) {
    b.writeByte(key(fieldNumber, WIRETYPE_LENGTH_DELIMITED));
    b.writeVarint(sizeOfAnnotation(value)); // length prefix

    b.writeByte(key(1, WIRETYPE_FIXED64)).writeLongLe(value.timestamp());
    writeUtf8Field(2, value.value(), b);
  }

  /** A map entry is an embedded messages: one for field the key and one for the value */
  int sizeOfMapEntryField(String key, String value) {
    int sizeOfMapEntry = sizeOfStringField(key) + sizeOfStringField(value);
    return sizeOfLengthDelimitedField(sizeOfMapEntry);
  }

  void writeMapEntryField(int fieldNumber, String key, String value, Buffer b) {
    b.writeByte(key(fieldNumber, WIRETYPE_LENGTH_DELIMITED));
    b.writeVarint(sizeOfStringField(key) + sizeOfStringField(value)); // length prefix

    writeUtf8Field(1, key, b);
    writeUtf8Field(2, value, b);
  }

  int sizeOfStringField(String string) {
    int sizeInBytes = Buffer.utf8SizeInBytes(string);
    return sizeOfLengthDelimitedField(sizeInBytes);
  }

  static int sizeOfLengthDelimitedField(int sizeInBytes) {
    return 1 + Buffer.varintSizeInBytes(sizeInBytes) + sizeInBytes; // tag + len + bytes
  }

  /** Encodes per ListOfSpans data type, where field one is repeated spans */
  public byte[] writeList(List<Span> spans) {
    int sizeInBytes = 0, lengthOfSpans = spans.size();
    if (lengthOfSpans == 0) return EMPTY_ARRAY;

    int[] sizesInBytes = new int[lengthOfSpans];
    for (int i = 0; i < lengthOfSpans; i++) {
      sizeInBytes += sizeOfLengthDelimitedField(sizesInBytes[i] = sizeInBytes(spans.get(i)));
    }
    Buffer result = new Buffer(sizeInBytes);
    for (int i = 0; i < lengthOfSpans; i++) {
      result.writeByte(key(1, WIRETYPE_LENGTH_DELIMITED));
      result.writeVarint(sizesInBytes[i]); // length prefix
      write(spans.get(i), result);
    }
    return result.toByteArray();
  }

  public int writeList(List<Span> spans, byte[] out, int pos) {
    int lengthOfSpans = spans.size();
    if (lengthOfSpans == 0) return 0;

    int initialPos = pos;
    Buffer result = new Buffer(out, pos);
    for (int i = 0; i < lengthOfSpans; i++) {
      result.writeByte(key(1, WIRETYPE_LENGTH_DELIMITED));
      result.writeVarint(sizeInBytes(spans.get(i))); // length prefix
      write(spans.get(i), result);
    }
    return result.pos - initialPos;
  }
}
