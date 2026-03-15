import Foundation

struct Station: Identifiable, Codable {
    let id: String
    let name: String
}

struct Program: Identifiable, Codable {
    let id: String
    let stationId: String
    let startTime: String // yyyyMMddHHmmss
    let endTime: String   // yyyyMMddHHmmss
    let title: String
    let description: String
    let performer: String
    let imageUrl: String?
    
    var duration: TimeInterval {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMddHHmmss"
        formatter.locale = Locale(identifier: "ja_JP")
        guard let start = formatter.date(from: startTime),
              let end = formatter.date(from: endTime) else {
            return 0
        }
        return end.timeIntervalSince(start)
    }
}
