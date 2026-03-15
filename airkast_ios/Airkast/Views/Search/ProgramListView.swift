import SwiftUI

struct ProgramListView: View {
    let station: Station
    @State private var programs: [Program] = []
    @State private var isLoading = false
    @State private var selectedDate = Date()
    
    var body: some View {
        ZStack {
            Color(.systemGroupedBackground).ignoresSafeArea()
            
            List(programs) { program in
                ProgramRow(program: program)
            }
            .listStyle(.insetGrouped)
        }
        .navigationTitle(station.name)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                DatePicker(
                    "",
                    selection: $selectedDate,
                    in: (Calendar.current.date(byAdding: .day, value: -6, to: Date())!)...(Date()),
                    displayedComponents: .date
                )
                .labelsHidden()
            }
        }
        .onChange(of: selectedDate) { _ in
            fetchPrograms()
        }
        .onAppear {
            if programs.isEmpty {
                fetchPrograms()
            }
        }
        .overlay {
            if isLoading {
                ProgressView()
                    .padding(20)
                    .background(.ultraThinMaterial)
                    .cornerRadius(12)
            }
        }
    }
    
    private func fetchPrograms() {
        isLoading = true
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd"
        let dateString = formatter.string(from: selectedDate)
        
        Task {
            do {
                programs = try await RadikoClient.shared.fetchPrograms(stationId: station.id, date: dateString)
            } catch {
                print("Failed to fetch programs: \(error)")
            }
            isLoading = false
        }
    }
}

struct ProgramRow: View {
    let program: Program
    @State private var isDownloading = false
    @State private var downloadProgress: Float = 0
    @State private var downloadStatus: String = ""
    
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(program.title)
                        .font(.headline)
                        .foregroundColor(.primary)
                        .lineLimit(2)
                    
                    Text(program.performer)
                        .font(.subheadline)
                        .foregroundColor(.blue)
                        .lineLimit(1)
                }
                
                Spacer()
                
                if !isDownloading && isFinished() {
                    Button(action: download) {
                        Image(systemName: "arrow.down.circle.fill")
                            .font(.system(size: 28))
                            .foregroundColor(.blue)
                    }
                    .buttonStyle(.plain)
                }
            }
            
            HStack {
                Label(formatTime(program.startTime) + " - " + formatTime(program.endTime), systemImage: "clock")
                    .font(.caption)
                    .foregroundColor(.secondary)
                
                Spacer()
            }
            
            if isDownloading {
                VStack(alignment: .leading, spacing: 6) {
                    ProgressView(value: downloadProgress)
                        .progressViewStyle(.linear)
                        .tint(.blue)
                    
                    HStack {
                        Text(downloadStatus)
                            .font(.caption2)
                            .foregroundColor(.blue)
                        Spacer()
                        Text(String(format: "%.0f%%", downloadProgress * 100))
                            .font(.caption2.monospacedDigit())
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.top, 4)
                .transition(.opacity)
            }
        }
        .padding(.vertical, 8)
    }
    
    private func isFinished() -> Bool {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMddHHmmss"
        guard let endDate = formatter.date(from: program.endTime) else { return false }
        return endDate < Date()
    }
    
    private func download() {
        guard let token = RadikoClient.shared.authToken else { return }
        withAnimation {
            isDownloading = true
        }
        Task {
            do {
                let url = try await HlsDownloader.shared.downloadProgram(program: program, authToken: token) { p, status in
                    DispatchQueue.main.async {
                        self.downloadProgress = p
                        self.downloadStatus = status
                    }
                }
                print("Downloaded to: \(url)")
                NotificationCenter.default.post(name: NSNotification.Name("DownloadCompleted"), object: nil)
            } catch {
                print("Download failed: \(error)")
            }
            withAnimation {
                isDownloading = false
            }
        }
    }
    
    private func formatTime(_ timeString: String) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMddHHmmss"
        if let date = formatter.date(from: timeString) {
            let outputFormatter = DateFormatter()
            outputFormatter.dateFormat = "HH:mm"
            return outputFormatter.string(from: date)
        }
        return ""
    }
}
