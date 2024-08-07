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
package io.trino.plugin.deltalake.delete;

import com.google.common.base.CharMatcher;
import io.delta.kernel.internal.deletionvectors.Base85Codec;
import io.delta.kernel.internal.deletionvectors.RoaringBitmapArray;
import io.delta.kernel.internal.deletionvectors.RoaringBitmapArrays;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoInputFile;
import io.trino.plugin.deltalake.transactionlog.DeletionVectorEntry;
import io.trino.spi.TrinoException;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.delta.kernel.internal.deletionvectors.Base85Codec.decodeUUID;
import static io.trino.plugin.deltalake.DeltaLakeErrorCode.DELTA_LAKE_INVALID_SCHEMA;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;

// https://github.com/delta-io/delta/blob/master/PROTOCOL.md#deletion-vector-format
public final class DeletionVectors
{
    private static final String UUID_MARKER = "u"; // relative path with random prefix on disk
    private static final String PATH_MARKER = "p"; // absolute path on disk
    private static final String INLINE_MARKER = "i"; // inline

    private static final CharMatcher ALPHANUMERIC = CharMatcher.inRange('A', 'Z').or(CharMatcher.inRange('a', 'z')).or(CharMatcher.inRange('0', '9')).precomputed();

    private DeletionVectors() {}

    public static RoaringBitmapArray readDeletionVectors(TrinoFileSystem fileSystem, Location location, DeletionVectorEntry deletionVector)
            throws IOException
    {
        if (deletionVector.storageType().equals(UUID_MARKER)) {
            TrinoInputFile inputFile = fileSystem.newInputFile(location.appendPath(toFileName(deletionVector.pathOrInlineDv())));
            byte[] buffer = readDeletionVector(inputFile, deletionVector.offset().orElseThrow(), deletionVector.sizeInBytes());
            return RoaringBitmapArrays.readFrom(buffer);
        }
        if (deletionVector.storageType().equals(INLINE_MARKER) || deletionVector.storageType().equals(PATH_MARKER)) {
            throw new TrinoException(NOT_SUPPORTED, "Unsupported storage type for deletion vector: " + deletionVector.storageType());
        }
        throw new IllegalArgumentException("Unexpected storage type: " + deletionVector.storageType());
    }

    public static String toFileName(String pathOrInlineDv)
    {
        int randomPrefixLength = pathOrInlineDv.length() - Base85Codec.ENCODED_UUID_LENGTH;
        String randomPrefix = pathOrInlineDv.substring(0, randomPrefixLength);
        checkArgument(ALPHANUMERIC.matchesAllOf(randomPrefix), "Random prefix must be alphanumeric: %s", randomPrefix);
        String prefix = randomPrefix.isEmpty() ? "" : randomPrefix + "/";
        String encodedUuid = pathOrInlineDv.substring(randomPrefixLength);
        UUID uuid = decodeUUID(encodedUuid);
        return "%sdeletion_vector_%s.bin".formatted(prefix, uuid);
    }

    public static byte[] readDeletionVector(TrinoInputFile inputFile, int offset, int expectedSize)
            throws IOException
    {
        byte[] bytes = new byte[expectedSize];
        try (DataInputStream inputStream = new DataInputStream(inputFile.newStream())) {
            checkState(inputStream.skip(offset) == offset);
            int actualSize = inputStream.readInt();
            if (actualSize != expectedSize) {
                throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "The size of deletion vector %s expects %s but got %s".formatted(inputFile.location(), expectedSize, actualSize));
            }
            inputStream.readFully(bytes);
            int checksum = inputStream.readInt();
            if (calculateChecksum(bytes) != checksum) {
                throw new TrinoException(DELTA_LAKE_INVALID_SCHEMA, "Checksum mismatch for deletion vector: " + inputFile.location());
            }
        }
        return bytes;
    }

    private static int calculateChecksum(byte[] data)
    {
        // Delta Lake allows integer overflow intentionally because it's fine from checksum perspective
        // https://github.com/delta-io/delta/blob/039a29abb4abc72ac5912651679233dc983398d6/spark/src/main/scala/org/apache/spark/sql/delta/storage/dv/DeletionVectorStore.scala#L115
        Checksum crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
