import SwiftUI
import AVFoundation

struct DownloadedProgram: Identifiable, Codable {
    let id: String
    let title: String
    let performer: String
    let fileName: String
    let duration: TimeInterval
}

struct DownloadListView: View {
    @State private var downloads: [DownloadedProgram] = []
    
    var body: some View {
        NavigationView {
            ZStack {
                if downloads.isEmpty {
                    VStack(spacing: 20) {
                        Image(systemName: "tray.and.arrow.down")
                            .font(.system(size: 60))
                            .foregroundColor(.secondary)
                        Text("ダウンロードした番組はありません")
                            .font(.headline)
                        Text("番組表から番組を選んでダウンロードしてください")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                } else {
                    List {
                        ForEach(downloads) { download in
                            DownloadRow(download: download, onPlay: { play(download) })
                        }
                        .onDelete(perform: deleteDownloads)
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("ダウンロード済み")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if !downloads.isEmpty {
                        EditButton()
                    }
                }
            }
            .onAppear(perform: loadDownloads)
            .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("DownloadCompleted"))) { _ in
                loadDownloads()
            }
        }
    }
    
    private func loadDownloads() {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        do {
            let fileURLs = try FileManager.default.contentsOfDirectory(at: documentsURL, includingPropertiesForKeys: nil)
            var newDownloads: [DownloadedProgram] = []
            
            for url in fileURLs where url.pathExtension == "m4a" && !url.lastPathComponent.contains(".tmp") {
                let asset = AVURLAsset(url: url)
                let duration = CMTimeGetSeconds(asset.duration)
                
                var title = url.lastPathComponent.replacingOccurrences(of: ".m4a", with: "")
                var performer = ""
                
                let metadataUrl = url.deletingPathExtension().appendingPathExtension("json")
                if FileManager.default.fileExists(atPath: metadataUrl.path),
                   let data = try? Data(contentsOf: metadataUrl),
                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
                    title = json["title"] ?? title
                    performer = json["performer"] ?? ""
                }
                
                newDownloads.append(DownloadedProgram(id: url.lastPathComponent, title: title, performer: performer, fileName: url.lastPathComponent, duration: duration))
            }
            newDownloads.sort { $0.title < $1.title } // Sort by title for now
            downloads = newDownloads
        } catch {
            print("Error loading downloads: \(error)")
        }
    }
    
    private func deleteDownloads(at offsets: IndexSet) {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        for index in offsets {
            let download = downloads[index]
            let fileURL = documentsURL.appendingPathComponent(download.fileName)
            let metadataURL = fileURL.deletingPathExtension().appendingPathExtension("json")
            do {
                try FileManager.default.removeItem(at: fileURL)
                if FileManager.default.fileExists(atPath: metadataURL.path) {
                    try FileManager.default.removeItem(at: metadataURL)
                }
            } catch {
                print("Failed to delete file: \(error)")
            }
        }
        downloads.remove(atOffsets: offsets)
    }
    
    private func play(_ download: DownloadedProgram) {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(download.fileName)
        AudioPlayer.shared.play(url: url, title: download.title, performer: download.performer)
    }
}

struct DownloadRow: View {
    let download: DownloadedProgram
    let onPlay: () -> Void
    
    var body: some View {
        Button(action: onPlay) {
            HStack(spacing: 16) {
                Image(systemName: "play.circle.fill")
                    .font(.title)
                    .foregroundColor(.blue)
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(download.title)
                        .font(.headline)
                        .lineLimit(1)
                    Text(download.performer)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                    
                    HStack {
                        Image(systemName: "clock")
                        Text(formatDuration(download.duration))
                    }
                    .font(.caption2)
                    .foregroundColor(.secondary)
                }
            }
            .padding(.vertical, 4)
        }
    }
    
    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d分 %02d秒", minutes, seconds)
    }
}
