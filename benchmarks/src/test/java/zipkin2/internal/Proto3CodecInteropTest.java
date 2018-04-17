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

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.junit.Test;
import zipkin2.codec.SpanBytesEncoder;
import zipkin2.proto3.Annotation;
import zipkin2.proto3.Endpoint;
import zipkin2.proto3.ListOfSpans;
import zipkin2.proto3.Span;

import static org.assertj.core.api.Assertions.assertThat;

public class Proto3CodecInteropTest {
  static final zipkin2.Endpoint ORDER = zipkin2.Endpoint.newBuilder()
    .serviceName("订单维护服务")
    .ip("2001:db8::c001")
    .build();

  static final zipkin2.Endpoint PROFILE = zipkin2.Endpoint.newBuilder()
    .serviceName("个人信息服务")
    .ip("192.168.99.101")
    .port(9000)
    .build();

  static final zipkin2.Span ZIPKIN_SPAN = zipkin2.Span.newBuilder()
    .traceId("4d1e00c0db9010db86154a4ba6e91385")
    .parentId("86154a4ba6e91385")
    .id("4d1e00c0db9010db")
    .kind(zipkin2.Span.Kind.SERVER)
    .name("个人信息查询")
    .timestamp(1472470996199000L)
    .duration(207000L)
    .localEndpoint(ORDER)
    .remoteEndpoint(PROFILE)
    .addAnnotation(1472470996199000L, "foo happened")
    .putTag("http.path", "/person/profile/query")
    .putTag("http.status_code", "403")
    .putTag("clnt/finagle.version", "6.45.0")
    .putTag("error", "此用户没有操作权限")
    .shared(true)
    .build();
  static final List<zipkin2.Span> ZIPKIN_SPANS = Arrays.asList(ZIPKIN_SPAN, ZIPKIN_SPAN);

  static final Span PROTO_SPAN = Span.newBuilder()
    .setTraceId(decodeHex(ZIPKIN_SPAN.traceId()))
    .setParentId(decodeHex(ZIPKIN_SPAN.parentId()))
    .setId(decodeHex(ZIPKIN_SPAN.id()))
    .setKind(Span.Kind.valueOf(ZIPKIN_SPAN.kind().name()))
    .setName(ZIPKIN_SPAN.name())
    .setTimestamp(ZIPKIN_SPAN.timestampAsLong())
    .setDuration(ZIPKIN_SPAN.durationAsLong())
    .setLocalEndpoint(Endpoint.newBuilder()
      .setServiceName(ORDER.serviceName())
      .setIpv6(ByteString.copyFrom(ORDER.ipv6Bytes())).build()
    )
    .setRemoteEndpoint(Endpoint.newBuilder()
      .setServiceName(PROFILE.serviceName())
      .setIpv4(ByteString.copyFrom(PROFILE.ipv4Bytes()))
      .setPort(PROFILE.portAsInt()).build()
    )
    .addAnnotations(Annotation.newBuilder()
      .setTimestamp(ZIPKIN_SPAN.annotations().get(0).timestamp())
      .setValue(ZIPKIN_SPAN.annotations().get(0).value())
      .build())
    .putAllTags(ZIPKIN_SPAN.tags())
    .setShared(true)
    .build();
  ListOfSpans PROTO_SPANS = ListOfSpans.newBuilder()
    .addSpans(PROTO_SPAN)
    .addSpans(PROTO_SPAN).build();

  Proto3SpanWriter writer = new Proto3SpanWriter();

  @Test public void encodeIsCompatible() throws Exception {
    byte[] buff = new byte[PROTO_SPAN.getSerializedSize()];
    CodedOutputStream out = CodedOutputStream.newInstance(buff);
    PROTO_SPAN.writeTo(out);

    assertThat(SpanBytesEncoder.PROTO3.encode(ZIPKIN_SPAN))
      .containsExactly(buff);
  }

  @Test public void encodeListIsCompatible_buff() throws Exception {
    byte[] buff = new byte[PROTO_SPANS.getSerializedSize()];
    CodedOutputStream out = CodedOutputStream.newInstance(buff);
    PROTO_SPANS.writeTo(out);

    byte[] zipkin_buff = new byte[10 + buff.length];
    assertThat(SpanBytesEncoder.PROTO3.encodeList(ZIPKIN_SPANS, zipkin_buff, 5))
      .isEqualTo(buff.length);

    assertThat(zipkin_buff)
      .startsWith(0, 0, 0, 0, 0)
      .containsSequence(buff)
      .endsWith(0, 0, 0, 0, 0);
  }

  @Test public void encodeListIsCompatible() throws Exception {
    byte[] buff = new byte[PROTO_SPANS.getSerializedSize()];
    CodedOutputStream out = CodedOutputStream.newInstance(buff);
    PROTO_SPANS.writeTo(out);

    assertThat(SpanBytesEncoder.PROTO3.encodeList(ZIPKIN_SPANS))
      .containsExactly(buff);
  }

  @Test public void sizeInBytes_matchesProto3() {
    assertThat(writer.sizeInBytes(ZIPKIN_SPAN))
      .isEqualTo(PROTO_SPAN.getSerializedSize());
  }

  @Test public void sizeOfStringField_matchesProto3() {
    assertThat(writer.sizeOfStringField(ORDER.serviceName()))
      .isEqualTo(CodedOutputStream.computeStringSize(1, ORDER.serviceName()));
  }

  @Test public void sizeOfAnnotationField_matchesProto3() {
    assertThat(writer.sizeOfAnnotationField(ZIPKIN_SPAN.annotations().get(0)))
      .isEqualTo(CodedOutputStream.computeMessageSize(10, Annotation.newBuilder()
        .setTimestamp(ZIPKIN_SPAN.annotations().get(0).timestamp())
        .setValue(ZIPKIN_SPAN.annotations().get(0).value())
        .build()));
  }

  @Test public void writeAnnotationField_matchesProto3() throws IOException {
    zipkin2.Annotation zipkinAnnotation = ZIPKIN_SPAN.annotations().get(0);
    Buffer buffer = new Buffer(writer.sizeOfAnnotationField(zipkinAnnotation));
    writer.writeAnnotationField(10, zipkinAnnotation, buffer);

    Annotation protoAnnotation = PROTO_SPAN.getAnnotations(0);
    byte[] buff = new byte[protoAnnotation.getSerializedSize() + 2];
    CodedOutputStream out = CodedOutputStream.newInstance(buff);
    out.writeMessage(10, protoAnnotation);

    assertThat(buffer.toByteArray())
      .containsExactly(buff);
  }

  @Test public void sizeOfMapEntryField_matchesProto3() {
    String key = "clnt/finagle.version", value = "6.45.0";
    assertThat(writer.sizeOfMapEntryField(key, value))
      .isEqualTo(Span.newBuilder().putTags(key, value).build().getSerializedSize());
  }

  @Test public void writeMapEntryField_matchesProto3() throws IOException {
    String key = "clnt/finagle.version", value = "6.45.0";
    Buffer buffer = new Buffer(writer.sizeOfMapEntryField(key, value));
    writer.writeMapEntryField(11, key, value, buffer);

    Span oneField = Span.newBuilder().putTags(key, value).build();
    byte[] buff = new byte[oneField.getSerializedSize()];
    CodedOutputStream out = CodedOutputStream.newInstance(buff);
    oneField.writeTo(out);

    assertThat(buffer.toByteArray())
      .containsExactly(buff);
  }

  @Test public void sizeOfEndpoint_matchesProto3() {
    assertThat(writer.sizeOfEndpoint(ZIPKIN_SPAN.localEndpoint()))
      .isEqualTo(PROTO_SPAN.getLocalEndpoint().getSerializedSize());

    assertThat(writer.sizeOfEndpoint(ZIPKIN_SPAN.remoteEndpoint()))
      .isEqualTo(PROTO_SPAN.getRemoteEndpoint().getSerializedSize());
  }

  @Test public void writeEndpointField_matchesProto3() throws IOException {
    writeEndpointField_matchesProto3(8, ZIPKIN_SPAN.localEndpoint(), PROTO_SPAN.getLocalEndpoint());
    writeEndpointField_matchesProto3(9, ZIPKIN_SPAN.remoteEndpoint(),
      PROTO_SPAN.getRemoteEndpoint());
  }

  void writeEndpointField_matchesProto3(int fieldNumber, zipkin2.Endpoint zipkinEndpoint,
    Endpoint protoEndpoint) throws IOException {
    Buffer buffer = new Buffer(writer.sizeOfEndpointField(zipkinEndpoint));
    writer.writeEndpointField(fieldNumber, zipkinEndpoint, buffer);

    byte[] buff = new byte[protoEndpoint.getSerializedSize() + 2];
    CodedOutputStream out = CodedOutputStream.newInstance(buff);
    out.writeMessage(fieldNumber, protoEndpoint);

    assertThat(buffer.toByteArray())
      .containsExactly(buff);
  }

  static ByteString decodeHex(String s) {
    try {
      return ByteString.copyFrom(Hex.decodeHex(s.toCharArray()));
    } catch (DecoderException e) {
      throw new AssertionError(e);
    }
  }
}
