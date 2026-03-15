import Foundation
import Combine
import AVFoundation
import MediaPlayer

class AudioPlayer: NSObject, ObservableObject {
    static let shared = AudioPlayer()
    
    private var player: AVPlayer?
    private var timeObserver: Any?
    
    @Published var isPlaying = false
    @Published var currentTime: TimeInterval = 0
    @Published var duration: TimeInterval = 0
    @Published var title: String = ""
    @Published var performer: String = ""
    @Published var playbackSpeed: Float = 1.0 {
        didSet {
            player?.rate = isPlaying ? playbackSpeed : 0.0
        }
    }
    
    override init() {
        super.init()
        setupRemoteCommandCenter()
        setupAudioSession()
    }
    
    func setupAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
            
            // Handle interruptions (e.g., phone calls)
            NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
                guard let userInfo = notification.userInfo,
                      let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
                      let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
                
                if type == .began {
                    self?.player?.pause()
                    self?.isPlaying = false
                } else if type == .ended {
                    if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt,
                       AVAudioSession.InterruptionOptions(rawValue: optionsValue).contains(.shouldResume) {
                        self?.player?.play()
                        self?.player?.rate = self?.playbackSpeed ?? 1.0
                        self?.isPlaying = true
                    }
                }
            }
            
            // Handle route changes (e.g., unplugging headphones)
            NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] notification in
                guard let userInfo = notification.userInfo,
                      let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }
                
                if reason == .oldDeviceUnavailable {
                    // Headphones disconnected, pause playback
                    DispatchQueue.main.async {
                        self?.player?.pause()
                        self?.isPlaying = false
                    }
                }
            }
        } catch {
            print("Failed to set audio session category: \(error)")
        }
    }
    
    func play(url: URL, title: String? = nil, performer: String? = nil) {
        print("AudioPlayer: Playing \(url.lastPathComponent)")
        
        let playerItem = AVPlayerItem(url: url)
        
        // Monitor status
        playerItem.publisher(for: \.status)
            .sink { [weak self] status in
                guard let self = self else { return }
                switch status {
                case .readyToPlay:
                    print("AudioPlayer: Item is ready to play")
                case .failed:
                    print("AudioPlayer Error: Item failed - \(String(describing: playerItem.error))")
                    if let error = playerItem.error as NSError? {
                        print("AudioPlayer Error Details: \(error.domain) \(error.code) \(error.userInfo)")
                    }
                    DispatchQueue.main.async {
                        self.isPlaying = false
                        self.title = ""
                        self.performer = ""
                    }
                case .unknown:
                    print("AudioPlayer: Item status is unknown")
                @unknown default:
                    break
                }
            }
            .store(in: &cancellables)
        
        NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: playerItem, queue: .main) { _ in
            print("AudioPlayer: Playback reached end")
        }
        
        duration = playerItem.asset.duration.seconds
        
        if let player = player {
            player.replaceCurrentItem(with: playerItem)
        } else {
            player = AVPlayer(playerItem: playerItem)
        }
        
        removeTimeObserver()
        addTimeObserver()
        
        self.title = title ?? "Airkast Program"
        self.performer = performer ?? "Radiko"
        
        player?.play()
        player?.rate = playbackSpeed
        isPlaying = true
        
        setupNowPlaying(title: self.title, performer: self.performer)
    }
    
    private var cancellables = Set<AnyCancellable>()
    
    func togglePlayPause() {
        guard let player = player else { return }
        DispatchQueue.main.async {
            if self.isPlaying {
                player.pause()
            } else {
                player.play()
                player.rate = self.playbackSpeed
            }
            self.isPlaying.toggle()
            self.updateNowPlayingPlaybackInfo()
        }
    }
    
    func seek(to time: TimeInterval) {
        player?.seek(to: CMTime(seconds: time, preferredTimescale: 600)) { [weak self] _ in
            self?.updateNowPlayingPlaybackInfo()
        }
    }
    
    func skipForward(seconds: TimeInterval) {
        let newTime = currentTime + seconds
        seek(to: min(newTime, duration))
    }
    
    func skipBackward(seconds: TimeInterval) {
        let newTime = currentTime - seconds
        seek(to: max(newTime, 0))
    }
    
    private func addTimeObserver() {
        timeObserver = player?.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1.0, preferredTimescale: 600), queue: .main) { [weak self] time in
            self?.currentTime = time.seconds
            // Don't update now playing too often to avoid overhead
        }
    }
    
    private func removeTimeObserver() {
        if let observer = timeObserver {
            player?.removeTimeObserver(observer)
            timeObserver = nil
        }
    }
    
    // MARK: - Remote Control & Now Playing
    
    private func setupNowPlaying(title: String?, performer: String?) {
        var nowPlayingInfo = [String: Any]()
        nowPlayingInfo[MPMediaItemPropertyTitle] = title ?? "Airkast Program"
        nowPlayingInfo[MPMediaItemPropertyArtist] = performer ?? "Radiko"
        
        if let currentItem = player?.currentItem {
            let duration = currentItem.asset.duration.seconds
            if !duration.isNaN {
                nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
            }
        }
        
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = player?.currentTime().seconds
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? playbackSpeed : 0.0
        
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
    
    private func updateNowPlayingPlaybackInfo() {
        var nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [String: Any]()
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = player?.currentTime().seconds
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? playbackSpeed : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
    
    private func setupRemoteCommandCenter() {
        let commandCenter = MPRemoteCommandCenter.shared()
        
        // Remove existing targets to avoid duplication
        commandCenter.playCommand.removeTarget(nil)
        commandCenter.pauseCommand.removeTarget(nil)
        commandCenter.togglePlayPauseCommand.removeTarget(nil)
        commandCenter.skipForwardCommand.removeTarget(nil)
        commandCenter.skipBackwardCommand.removeTarget(nil)
        
        commandCenter.playCommand.addTarget { [weak self] event in
            if let self = self, !self.isPlaying {
                self.togglePlayPause()
                return .success
            }
            return .commandFailed
        }
        
        commandCenter.pauseCommand.addTarget { [weak self] event in
            if let self = self, self.isPlaying {
                self.togglePlayPause()
                return .success
            }
            return .commandFailed
        }
        
        commandCenter.togglePlayPauseCommand.addTarget { [weak self] event in
            self?.togglePlayPause()
            return .success
        }
        
        commandCenter.skipForwardCommand.preferredIntervals = [15]
        commandCenter.skipForwardCommand.addTarget { [weak self] event in
            self?.skipForward(seconds: 15)
            return .success
        }
        
        commandCenter.skipBackwardCommand.preferredIntervals = [15]
        commandCenter.skipBackwardCommand.addTarget { [weak self] event in
            self?.skipBackward(seconds: 15)
            return .success
        }
    }
}
