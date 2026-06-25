import Foundation

/// A minimal ZIP archive writer using the STORE method (no compression). Enough to
/// assemble OOXML packages (`.xlsx`, `.docx`), which are ZIP containers of XML parts.
/// Emits standards-compliant local file headers, a central directory, and an
/// end-of-central-directory record with CRC-32 checksums, so the output opens in Excel,
/// Word, Numbers, Pages, and any conformant reader.
struct ZipArchiveWriter {
    private struct Entry {
        let name: String
        let size: UInt32
        let crc: UInt32
        let offset: UInt32
    }

    private var entries: [Entry] = []
    private var buffer = Data()

    /// Appends a stored file. `name` is the in-archive path (e.g. `"xl/workbook.xml"`).
    mutating func addFile(_ name: String, _ data: Data) {
        let crc = ZipArchiveWriter.crc32(data)
        let offset = UInt32(buffer.count)
        let nameBytes = Array(name.utf8)

        buffer.append(Self.u32(0x04034b50))          // local file header signature
        buffer.append(Self.u16(20))                  // version needed to extract
        buffer.append(Self.u16(0))                   // general purpose flags
        buffer.append(Self.u16(0))                   // compression method: 0 = store
        buffer.append(Self.u16(0))                   // last mod time
        buffer.append(Self.u16(0))                   // last mod date
        buffer.append(Self.u32(crc))
        buffer.append(Self.u32(UInt32(data.count)))  // compressed size
        buffer.append(Self.u32(UInt32(data.count)))  // uncompressed size
        buffer.append(Self.u16(UInt16(nameBytes.count)))
        buffer.append(Self.u16(0))                   // extra field length
        buffer.append(contentsOf: nameBytes)
        buffer.append(data)

        entries.append(Entry(name: name, size: UInt32(data.count), crc: crc, offset: offset))
    }

    /// Convenience for XML/text parts.
    mutating func addFile(_ name: String, _ string: String) {
        addFile(name, Data(string.utf8))
    }

    /// Closes the archive and returns the complete ZIP bytes.
    mutating func finalize() -> Data {
        let centralDirectoryStart = UInt32(buffer.count)
        var centralDirectory = Data()

        for entry in entries {
            let nameBytes = Array(entry.name.utf8)
            centralDirectory.append(Self.u32(0x02014b50))   // central directory signature
            centralDirectory.append(Self.u16(20))           // version made by
            centralDirectory.append(Self.u16(20))           // version needed
            centralDirectory.append(Self.u16(0))            // flags
            centralDirectory.append(Self.u16(0))            // method
            centralDirectory.append(Self.u16(0))            // time
            centralDirectory.append(Self.u16(0))            // date
            centralDirectory.append(Self.u32(entry.crc))
            centralDirectory.append(Self.u32(entry.size))   // compressed size
            centralDirectory.append(Self.u32(entry.size))   // uncompressed size
            centralDirectory.append(Self.u16(UInt16(nameBytes.count)))
            centralDirectory.append(Self.u16(0))            // extra length
            centralDirectory.append(Self.u16(0))            // comment length
            centralDirectory.append(Self.u16(0))            // disk number start
            centralDirectory.append(Self.u16(0))            // internal attributes
            centralDirectory.append(Self.u32(0))            // external attributes
            centralDirectory.append(Self.u32(entry.offset))
            centralDirectory.append(contentsOf: nameBytes)
        }
        buffer.append(centralDirectory)

        var eocd = Data()
        eocd.append(Self.u32(0x06054b50))                       // end of central directory signature
        eocd.append(Self.u16(0))                                // this disk number
        eocd.append(Self.u16(0))                                // disk with central directory
        eocd.append(Self.u16(UInt16(entries.count)))            // entries on this disk
        eocd.append(Self.u16(UInt16(entries.count)))            // total entries
        eocd.append(Self.u32(UInt32(centralDirectory.count)))   // central directory size
        eocd.append(Self.u32(centralDirectoryStart))            // central directory offset
        eocd.append(Self.u16(0))                                // comment length
        buffer.append(eocd)

        return buffer
    }

    // MARK: - Little-endian encoding

    private static func u16(_ value: UInt16) -> Data {
        Data([UInt8(value & 0xff), UInt8((value >> 8) & 0xff)])
    }

    private static func u32(_ value: UInt32) -> Data {
        Data([
            UInt8(value & 0xff),
            UInt8((value >> 8) & 0xff),
            UInt8((value >> 16) & 0xff),
            UInt8((value >> 24) & 0xff)
        ])
    }

    // MARK: - CRC-32 (IEEE 802.3, polynomial 0xEDB88320)

    private static let crcTable: [UInt32] = {
        (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) != 0 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()

    private static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data {
            crc = crcTable[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8)
        }
        return crc ^ 0xffffffff
    }
}
