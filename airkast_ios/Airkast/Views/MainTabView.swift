import SwiftUI

struct MainTabView: View {
    @StateObject private var player = AudioPlayer.shared
    @State private var showFullPlayer = false
    
    var body: some View {
        ZStack(alignment: .bottom) {
            TabView {
                StationListView()
                    .tabItem {
                        Label("番組を探す", systemImage: "magnifyingglass")
                    }
                
                DownloadListView()
                    .tabItem {
                        Label("ダウンロード", systemImage: "arrow.down.circle")
                    }
            }
            
            // MiniPlayer
            if !player.title.isEmpty {
                VStack(spacing: 0) {
                    HStack(spacing: 12) {
                        // Thumbnail placeholder
                        RoundedRectangle(cornerRadius: 6)
                            .fill(LinearGradient(gradient: Gradient(colors: [.blue, .purple]), startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 44, height: 44)
                            .overlay(Image(systemName: "radio").foregroundColor(.white.opacity(0.8)).font(.system(size: 20)))
                        
                        VStack(alignment: .leading, spacing: 2) {
                            Text(player.title)
                                .font(.subheadline)
                                .fontWeight(.semibold)
                                .lineLimit(1)
                            Text(player.performer)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        
                        Spacer()
                        
                        Button(action: { player.togglePlayPause() }) {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                .font(.title2)
                                .foregroundColor(.primary)
                                .frame(width: 44, height: 44)
                        }
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 64)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        showFullPlayer = true
                    }
                    
                    // Slim Progress Bar
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Rectangle()
                                .fill(Color.secondary.opacity(0.2))
                            Rectangle()
                                .fill(Color.blue)
                                .frame(width: geo.size.width * CGFloat(player.currentTime / max(player.duration, 1.0)))
                        }
                    }
                    .frame(height: 2)
                }
                .background(.ultraThinMaterial)
                .cornerRadius(12)
                .padding(.horizontal, 8)
                .padding(.bottom, 54) // Just above the tab bar
                .shadow(color: Color.black.opacity(0.15), radius: 10, x: 0, y: 5)
            }
        }
        .sheet(isPresented: $showFullPlayer) {
            PlayerView()
        }
    }
}

struct StationListView: View {
    @StateObject private var client = RadikoClient.shared
    @State private var stations: [Station] = []
    @State private var isLoading = false
    @State private var showAreaPicker = false
    
    var body: some View {
        NavigationView {
            ZStack {
                Color(.systemGroupedBackground).ignoresSafeArea()
                
                if stations.isEmpty && !isLoading {
                    VStack(spacing: 16) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.system(size: 50))
                            .foregroundColor(.secondary)
                        Text("放送局が見つかりません")
                            .font(.headline)
                        Button("再読み込み") { fetchStations() }
                            .buttonStyle(.bordered)
                    }
                } else {
                    List(stations) { station in
                        NavigationLink(destination: ProgramListView(station: station)) {
                            HStack(spacing: 16) {
                                // Dynamic Color Icon
                                ZStack {
                                    Circle()
                                        .fill(colorFor(station.name))
                                        .frame(width: 40, height: 40)
                                    Text(String(station.name.prefix(1)))
                                        .foregroundColor(.white)
                                        .font(.headline)
                                }
                                
                                Text(station.name)
                                    .font(.body)
                                    .fontWeight(.medium)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle(client.areaId ?? "放送局")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { showAreaPicker = true }) {
                        HStack(spacing: 4) {
                            Image(systemName: "mappin.and.ellipse")
                            Text("エリア")
                                .font(.subheadline)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(Color.blue.opacity(0.1)))
                    }
                }
            }
            .sheet(isPresented: $showAreaPicker) {
                AreaPickerView { newAreaId in
                    changeArea(to: newAreaId)
                }
            }
            .onAppear {
                fetchStations()
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
    }
    
    private func colorFor(_ name: String) -> Color {
        let colors: [Color] = [.blue, .purple, .orange, .pink, .teal, .indigo, .green]
        let index = abs(name.hashValue) % colors.count
        return colors[index]
    }
    
    private func fetchStations() {
        guard stations.isEmpty else { return }
        isLoading = true
        Task {
            do {
                if client.areaId == nil {
                    try await client.authenticate()
                }
                if let areaId = client.areaId {
                    stations = try await client.fetchStations(areaId: areaId)
                }
            } catch {
                print("Failed to fetch stations: \(error)")
            }
            isLoading = false
        }
    }
    
    private func changeArea(to areaId: String) {
        isLoading = true
        client.areaId = areaId
        Task {
            do {
                stations = try await client.fetchStations(areaId: areaId)
            } catch {
                print("Failed to change area: \(error)")
            }
            isLoading = false
        }
    }
}

struct AreaPickerView: View {
    @Environment(\.dismiss) var dismiss
    let onSelect: (String) -> Void
    
    let areas = [
        ("JP1", "北海道"),
        ("JP2", "青森県"),
        ("JP3", "岩手県"),
        ("JP4", "宮城県"),
        ("JP13", "東京都"),
        ("JP14", "神奈川県"),
        ("JP23", "愛知県"),
        ("JP27", "大阪府"),
        ("JP28", "兵庫県"),
        ("JP33", "岡山県"),
        ("JP34", "広島県"),
        ("JP40", "福岡県")
    ]
    
    var body: some View {
        NavigationView {
            List(areas, id: \.0) { area in
                Button(action: {
                    onSelect(area.0)
                    dismiss()
                }) {
                    HStack {
                        Text(area.1)
                        Spacer()
                        Text(area.0)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("エリア選択")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("キャンセル") { dismiss() }
                }
            }
        }
    }
}
