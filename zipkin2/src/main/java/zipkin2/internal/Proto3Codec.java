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
import zipkin2.Span;

//@Immutable
public final class Proto3Codec {
  // Maximum length of a variable length string we will decode. Same constraint as thrift
  static final int STRING_LENGTH_LIMIT = 1 * 1024 * 1024;

  /**
   * Define the wire types we use.
   *
   * <p>See https://developers.google.com/protocol-buffers/docs/encoding#structure
   */
  static final int
    WIRETYPE_VARINT = 0,
    WIRETYPE_FIXED64 = 1,
    WIRETYPE_LENGTH_DELIMITED = 2;

  final Proto3SpanWriter writer = new Proto3SpanWriter();

  public int sizeInBytes(Span input) {
    return writer.sizeInBytes(input);
  }

  public byte[] write(Span span) {
    Buffer b = new Buffer(writer.sizeInBytes(span));
    writer.write(span, b);
    return b.toByteArray();
  }

  public byte[] writeList(List<Span> spans) {
    return writer.writeList(spans);
  }

  public int writeList(List<Span> spans, byte[] out, int pos) {
    return writer.writeList(spans, out, pos);
  }
}
