import SwiftUI

struct PlayerView: View {
    @StateObject private var player = AudioPlayer.shared
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        ZStack {
            // Adaptive Background Gradient
            LinearGradient(
                gradient: Gradient(colors: [
                    Color(.systemBackground),
                    Color.blue.opacity(0.1),
                    Color.purple.opacity(0.1)
                ]),
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            
            VStack(spacing: 24) {
                // Top Bar
                HStack {
                    Button(action: { dismiss() }) {
                        Image(systemName: "chevron.down")
                            .font(.title3.bold())
                            .foregroundColor(.primary)
                            .padding()
                            .background(Circle().fill(Color.secondary.opacity(0.2)))
                    }
                    Spacer()
                    Text("再生中")
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Spacer()
                    // Dummy spacer to balance the close button
                    Circle().fill(Color.clear).frame(width: 44, height: 44).padding()
                }
                
                Spacer()
                
                // Album Art Placeholder
                ZStack {
                    RoundedRectangle(cornerRadius: 24)
                        .fill(LinearGradient(gradient: Gradient(colors: [Color.blue, Color.purple]), startPoint: .topLeading, endPoint: .bottomTrailing))
                        .aspectRatio(1, contentMode: .fit)
                        .shadow(color: Color.black.opacity(0.2), radius: 20, x: 0, y: 10)
                    
                    Image(systemName: "radio")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 100)
                        .foregroundColor(.white.opacity(0.8))
                }
                .padding(.horizontal, 40)
                
                Spacer()
                
                // Program Info
                VStack(spacing: 8) {
                    Text(player.title)
                        .font(.title2)
                        .fontWeight(.bold)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                    
                    Text(player.performer)
                        .font(.headline)
                        .foregroundColor(.blue)
                }
                
                // Progress Slider
                VStack(spacing: 8) {
                    Slider(value: Binding(get: { player.currentTime }, set: { player.seek(to: $0) }), in: 0...(max(player.duration, 1.0)))
                        .accentColor(.blue)
                    
                    HStack {
                        Text(formatTime(player.currentTime))
                        Spacer()
                        Text(formatTime(player.duration))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundColor(.secondary)
                }
                .padding(.horizontal, 30)
                
                // Playback Controls
                HStack(spacing: 40) {
                    Button(action: { player.skipBackward(seconds: 15) }) {
                        Image(systemName: "gobackward.15")
                            .font(.system(size: 34))
                            .foregroundColor(.primary)
                    }
                    
                    Button(action: { player.togglePlayPause() }) {
                        Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 80, height: 80)
                            .foregroundColor(.blue)
                            .background(Circle().fill(Color.white).padding(5))
                            .shadow(radius: 5)
                    }
                    
                    Button(action: { player.skipForward(seconds: 15) }) {
                        Image(systemName: "goforward.15")
                            .font(.system(size: 34))
                            .foregroundColor(.primary)
                    }
                }
                
                // Speed Control
                VStack(spacing: 12) {
                    Text("再生速度")
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.secondary)
                        .textCase(.uppercase)
                    
                    Picker("再生速度", selection: $player.playbackSpeed) {
                        Text("0.5x").tag(Float(0.5))
                        Text("1.0x").tag(Float(1.0))
                        Text("1.5x").tag(Float(1.5))
                        Text("2.0x").tag(Float(2.0))
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 250)
                }
                .padding(.top)
                
                Spacer()
            }
            .padding()
        }
    }
    
    private func formatTime(_ time: TimeInterval) -> String {
        if time.isNaN { return "00:00" }
        let mins = Int(time) / 60
        let secs = Int(time) % 60
        return String(format: "%02d:%02d", mins, secs)
    }
}
