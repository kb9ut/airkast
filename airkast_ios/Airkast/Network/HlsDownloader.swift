import Foundation
import AVFoundation
import CommonCrypto

enum DownloadError: Error {
    case invalidPlaylist
    case downloadFailed(String)
    case muxingFailed
}

class HlsDownloader: NSObject {
    static let shared = HlsDownloader()
    
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.httpCookieStorage = .shared
        config.httpShouldSetCookies = true
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        ]
        return URLSession(configuration: config)
    }()
    
    func downloadProgram(program: Program, authToken: String, progress: @escaping (Float, String) -> Void) async throws -> URL {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMddHHmmss"
        formatter.locale = Locale(identifier: "ja_JP")
        
        guard let startTotal = formatter.date(from: program.startTime),
              let endTotal = formatter.date(from: program.endTime) else {
            throw DownloadError.invalidPlaylist
        }
        
        let lsid = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let chunkDuration: TimeInterval = 300 // 5 minutes
        
        let tempAdtsUrl = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".aac")
        FileManager.default.createFile(atPath: tempAdtsUrl.path, contents: nil)
        let fileHandle = try FileHandle(forWritingTo: tempAdtsUrl)
        
        var totalBytes: Int64 = 0
        var currentStart = startTotal
        let totalTime = endTotal.timeIntervalSince(startTotal)
        
        func createRequest(for url: URL) -> URLRequest {
            var request = URLRequest(url: url)
            request.addValue(authToken, forHTTPHeaderField: "X-Radiko-Authtoken")
            request.addValue("pc_html5", forHTTPHeaderField: "X-Radiko-App")
            request.addValue("0.0.1", forHTTPHeaderField: "X-Radiko-App-Version")
            request.addValue("dummy_user", forHTTPHeaderField: "X-Radiko-User")
            request.addValue("pc", forHTTPHeaderField: "X-Radiko-Device")
            request.addValue("https://radiko.jp/", forHTTPHeaderField: "Referer")
            request.addValue("https://radiko.jp", forHTTPHeaderField: "Origin")
            return request
        }
        
        print("Starting chunked download for \(program.title) using lsid: \(lsid)")
        
        while currentStart < endTotal {
            let currentEnd = min(currentStart.addingTimeInterval(chunkDuration), endTotal)
            let ft = formatter.string(from: currentStart)
            let to = formatter.string(from: currentEnd)
            let duration = Int(currentEnd.timeIntervalSince(currentStart))
            
            let hlsUrlStr = "https://tf-f-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8" +
                "?station_id=\(program.stationId)" +
                "&l=\(duration)" +
                "&ft=\(ft)" +
                "&to=\(to)" +
                "&start_at=\(ft)" +
                "&end_at=\(to)" +
                "&lsid=\(lsid)" +
                "&type=b"
            
            guard let url = URL(string: hlsUrlStr) else { continue }
            print("Fetching chunk: \(ft) -> \(to) (\(duration)s)")
            
            do {
                let (mediaPlaylistUrl, mediaContent) = try await fetchMediaPlaylistInfo(url: url, createRequest: createRequest)
                let segmentUrls = parseSegments(content: mediaContent, base: mediaPlaylistUrl)
                let baseSequence = parseMediaSequence(content: mediaContent)
                
                let (aesKey, _) = try await fetchAESKey(content: mediaContent, base: mediaPlaylistUrl, createRequest: createRequest)
                
                for (index, segmentUrl) in segmentUrls.enumerated() {
                    let (segData, segResponse) = try await session.data(for: createRequest(for: segmentUrl))
                    if let httpSegResponse = segResponse as? HTTPURLResponse, httpSegResponse.statusCode == 200 {
                        var finalData = segData
                        if let key = aesKey {
                            let iv = ivFor(index: baseSequence + index)
                            if let decrypted = decrypt(data: segData, key: key, iv: iv) {
                                finalData = decrypted
                            }
                        }
                        
                        // Strip ID3 tags if present
                        let strippedData = stripID3(finalData)
                        totalBytes += Int64(strippedData.count)
                        fileHandle.write(strippedData)
                    }
                }
            } catch {
                print("Failed to download chunk \(ft): \(error)")
            }
            
            currentStart = currentEnd
            progress(Float(currentStart.timeIntervalSince(startTotal) / totalTime) * 0.9, "ダウンロード中...")
        }
        
        print("Total downloaded bytes: \(totalBytes)")
        fileHandle.closeFile()
        
        let finalUrl = getDocumentsDirectory().appendingPathComponent("\(program.id).m4a")
        let tempM4aUrl = finalUrl.appendingPathExtension("tmp")
        
        if FileManager.default.fileExists(atPath: finalUrl.path) {
            try FileManager.default.removeItem(at: finalUrl)
        }
        
        do {
            try await convertAACToM4A(input: tempAdtsUrl, output: tempM4aUrl) { p in
                progress(0.9 + (p * 0.1), "変換中 (Muxing)...")
            }
            try FileManager.default.moveItem(at: tempM4aUrl, to: finalUrl)
            
            // Save metadata sidecar
            let metadata = [
                "id": program.id,
                "title": program.title,
                "performer": program.performer,
                "stationId": program.stationId,
                "startTime": program.startTime,
                "endTime": program.endTime
            ]
            if let metadataData = try? JSONSerialization.data(withJSONObject: metadata, options: .prettyPrinted) {
                let metadataUrl = finalUrl.deletingPathExtension().appendingPathExtension("json")
                try? metadataData.write(to: metadataUrl)
            }
            
            print("Successfully converted and moved to: \(finalUrl)")
        } catch {
            print("Muxing failed, using raw fallback: \(error)")
            try FileManager.default.moveItem(at: tempAdtsUrl, to: finalUrl)
        }
        
        try? FileManager.default.removeItem(at: tempAdtsUrl)
        return finalUrl
    }
    
    private func stripID3(_ data: Data) -> Data {
        // ID3 tags start with "ID3" (0x49 0x44 0x33)
        // Header is 10 bytes: ID3 (3) + version (2) + flags (1) + size (4)
        if data.count > 10, data[0] == 0x49, data[1] == 0x44, data[2] == 0x33 {
            let s1 = Int(data[6] & 0x7F)
            let s2 = Int(data[7] & 0x7F)
            let s3 = Int(data[8] & 0x7F)
            let s4 = Int(data[9] & 0x7F)
            let size = (s1 << 21) | (s2 << 14) | (s3 << 7) | s4
            let totalHeaderSize = 10 + size
            if data.count > totalHeaderSize {
                return data.advanced(by: totalHeaderSize)
            }
        }
        return data
    }
    
    private func parseMediaSequence(content: String) -> Int {
        let lines = content.components(separatedBy: .newlines)
        for line in lines {
            if line.contains("#EXT-X-MEDIA-SEQUENCE") {
                let parts = line.components(separatedBy: ":")
                if parts.count > 1, let seq = Int(parts[1].trimmingCharacters(in: .whitespaces)) {
                    return seq
                }
            }
        }
        return 0
    }
    
    private func fetchPlaylistSegments(url: URL, createRequest: (URL) -> URLRequest) async throws -> [URL] {
        let (data, response) = try await session.data(for: createRequest(url))
        guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
            throw DownloadError.invalidPlaylist
        }
        
        let content = String(data: data, encoding: .utf8) ?? ""
        if content.contains("#EXT-X-STREAM-INF") {
            if let relativeUrl = parseMediaPlaylistUrl(content: content) {
                let mediaUrl = resolveUrl(base: url, relative: relativeUrl)
                return try await fetchPlaylistSegments(url: mediaUrl, createRequest: createRequest)
            }
        }
        
        return parseSegments(content: content, base: url)
    }
    
    private func fetchMediaPlaylistInfo(url: URL, createRequest: (URL) -> URLRequest) async throws -> (URL, String) {
        let (data, response) = try await session.data(for: createRequest(url))
        let content = String(data: data, encoding: .utf8) ?? ""
        if content.contains("#EXT-X-STREAM-INF") {
            if let relativeUrl = parseMediaPlaylistUrl(content: content) {
                let mediaUrl = resolveUrl(base: url, relative: relativeUrl)
                return try await fetchMediaPlaylistInfo(url: mediaUrl, createRequest: createRequest)
            }
        }
        return (url, content)
    }
    
    private func parseMediaPlaylistUrl(content: String) -> String? {
        let lines = content.components(separatedBy: .newlines)
        for (index, line) in lines.enumerated() {
            if line.contains("#EXT-X-STREAM-INF") {
                if index + 1 < lines.count {
                    return lines[index+1].trimmingCharacters(in: .whitespaces)
                }
            }
        }
        return nil
    }
    
    private func parseSegments(content: String, base: URL) -> [URL] {
        let lines = content.components(separatedBy: .newlines)
        return lines.compactMap { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty || trimmed.hasPrefix("#") { return nil }
            return resolveUrl(base: base, relative: trimmed)
        }
    }
    
    private func resolveUrl(base: URL, relative: String) -> URL {
        if relative.hasPrefix("http") { return URL(string: relative)! }
        return URL(string: relative, relativeTo: base)!.absoluteURL
    }
    
    private func getDocumentsDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    
    private func convertAACToM4A(input: URL, output: URL, progress: @escaping (Float) -> Void) async throws {
        let asset = AVAsset(url: input)
        
        // Check if asset is readable/exportable
        let tracks = try? await asset.loadTracks(withMediaType: .audio)
        if tracks == nil || tracks?.isEmpty == true {
            print("Muxing: No audio tracks found in concatenated file. Falling back to raw move.")
            try FileManager.default.moveItem(at: input, to: output)
            return
        }
        
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            print("Muxing: Could not create AVAssetExportSession. Falling back to raw move.")
            try FileManager.default.moveItem(at: input, to: output)
            return
        }
        
        exportSession.outputURL = output
        exportSession.outputFileType = .m4a
        
        // Poll for progress
        let progressHandler = Task {
            while exportSession.status == .waiting || exportSession.status == .exporting {
                progress(exportSession.progress)
                try? await Task.sleep(nanoseconds: 100_000_000) // 0.1s
            }
        }
        
        await exportSession.export()
        progressHandler.cancel()
        
        switch exportSession.status {
        case .completed:
            progress(1.0)
            print("Muxing: Export completed successfully.")
            try? FileManager.default.removeItem(at: input)
        case .failed:
            let errorMsg = exportSession.error?.localizedDescription ?? "Unknown error"
            print("Muxing: Export failed: \(errorMsg). Underlying: \(String(describing: exportSession.error))")
            // Fallback: move as is even if it might be problematic
            if FileManager.default.fileExists(atPath: output.path) {
                try? FileManager.default.removeItem(at: output)
            }
            try FileManager.default.moveItem(at: input, to: output)
        case .cancelled:
            print("Muxing: Export cancelled.")
            throw DownloadError.muxingFailed
        default:
            print("Muxing: Export ended with status \(exportSession.status.rawValue)")
            throw DownloadError.muxingFailed
        }
    }
    
    private func fetchAESKey(content: String, base: URL, createRequest: (URL) -> URLRequest) async throws -> (Data?, URL?) {
        let lines = content.components(separatedBy: .newlines)
        for line in lines {
            if line.contains("#EXT-X-KEY"), line.contains("METHOD=AES-128") {
                // Extract URI="URL"
                let scanner = Scanner(string: line)
                _ = scanner.scanUpToString("URI=\"")
                _ = scanner.scanString("URI=\"")
                if let urlStr = scanner.scanUpToString("\"") {
                    let keyUrl = resolveUrl(base: base, relative: urlStr)
                    let (data, _) = try await session.data(for: createRequest(keyUrl))
                    return (data, keyUrl)
                }
            }
        }
        return (nil, nil)
    }
    
    private func ivFor(index: Int) -> Data {
        var iv = Data(count: 16)
        var sequenceNumber = UInt64(index).bigEndian
        withUnsafePointer(to: &sequenceNumber) { ptr in
            iv.replaceSubrange(8..<16, with: Data(bytes: ptr, count: 8))
        }
        return iv
    }
    
    private func decrypt(data: Data, key: Data, iv: Data) -> Data? {
        let size = data.count + kCCBlockSizeAES128
        var decrypted = Data(count: size)
        var numBytesDecrypted: Int = 0
        
        let status = decrypted.withUnsafeMutableBytes { decryptedBytes in
            data.withUnsafeBytes { dataBytes in
                key.withUnsafeBytes { keyBytes in
                    iv.withUnsafeBytes { ivBytes in
                        CCCrypt(CCOperation(kCCDecrypt),
                                CCAlgorithm(kCCAlgorithmAES),
                                CCOptions(kCCOptionPKCS7Padding),
                                keyBytes.baseAddress, kCCKeySizeAES128,
                                ivBytes.baseAddress,
                                dataBytes.baseAddress, data.count,
                                decryptedBytes.baseAddress, size,
                                &numBytesDecrypted)
                    }
                }
            }
        }
        
        if status == kCCSuccess {
            return decrypted.prefix(numBytesDecrypted)
        }
        return nil
    }
}
