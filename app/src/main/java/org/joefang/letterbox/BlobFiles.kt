package org.joefang.letterbox

import java.io.File

/**
 * File-system half of the content-addressable blob store.
 *
 * The store spans two places that can disagree — the `blobs` table and the
 * `cas/` directory — and reconciling them is pure file-system work. Keeping it
 * here, free of Room and Android, means it can be tested against a real
 * temporary directory with no database and no device.
 */

/**
 * Delete every file directly in [dir] whose name is not in [keep], returning the
 * number of bytes reclaimed.
 *
 * [keep] is the set of blob hashes the database knows about; since a blob's hash
 * *is* its filename, set membership is the whole reconciliation rule. Anything
 * else in the directory is unreachable content.
 *
 * Total: a missing or unreadable [dir] reclaims nothing rather than failing, and
 * a file that resists deletion is skipped rather than counted. Sub-directories
 * are left alone — the store is flat, so a directory here was not put there by
 * the store.
 */
internal fun reclaimUnknownFiles(dir: File, keep: Set<String>): Long =
    (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name !in keep }
        // Bytes actually freed: identity 0, associative +, and a failed delete
        // contributes the identity.
        .fold(0L) { reclaimed, file ->
            val size = file.length()
            if (file.delete()) reclaimed + size else reclaimed
        }
