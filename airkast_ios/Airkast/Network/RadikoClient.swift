import Foundation
import Combine

enum RadikoError: Error {
    case auth1Failed(String)
    case partialKeyFailed
    case auth2Failed(String)
    case fetchStationsFailed(String)
    case fetchProgramsFailed(String)
    case invalidResponse
    case areaOutside
}

class RadikoClient: NSObject, ObservableObject, XMLParserDelegate {
    static let shared = RadikoClient()
    
    @Published var authToken: String?
    @Published var areaId: String?
    
    private let PC_FULL_KEY = "bcd151073c03b352e1ef2fd66c32209da9ca0afa"
    
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.httpCookieStorage = .shared
        config.httpShouldSetCookies = true
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "X-Radiko-App": "pc_html5",
            "X-Radiko-App-Version": "0.0.1",
            "X-Radiko-User": "dummy_user",
            "X-Radiko-Device": "pc"
        ]
        return URLSession(configuration: config)
    }()
    
    func authenticate() async throws {
        // Initial request to get base cookies
        let radikoUrl = URL(string: "https://radiko.jp/")!
        let (_, _) = try await session.data(from: radikoUrl)
        
        let (keyLength, keyOffset) = try await auth1()
        let partialKey = try getPartialKey(offset: keyOffset, length: keyLength)
        self.areaId = try await auth2(partialKey: partialKey)
        copyCookiesToSmartStream()
        print("Authenticated: areaId=\(self.areaId ?? "unknown")")
    }
    
    private func copyCookiesToSmartStream() {
        let storage = HTTPCookieStorage.shared
        
        // More aggressive search for cookies
        guard let allCookies = storage.cookies else { 
            print("No cookies found in storage at all")
            return 
        }
        
        let radikoCookies = allCookies.filter { $0.domain.contains("radiko.jp") }
        print("Found \(radikoCookies.count) radiko cookies among \(allCookies.count) total cookies")
        
        if radikoCookies.isEmpty {
            print("No radiko.jp cookies found. Domains present: \(Set(allCookies.map { $0.domain }))")
        }
        
        let domains = [
            "smartstream.ne.jp",
            "si-f-radiko.smartstream.ne.jp",
            "tf-f-rpaa-radiko.smartstream.ne.jp",
            "tf-rpaa.smartstream.ne.jp"
        ]
        
        for cookie in radikoCookies {
            for domain in domains {
                var properties = cookie.properties ?? [:]
                properties[.domain] = domain
                if domain.contains("smartstream.ne.jp") {
                    properties[.originURL] = URL(string: "https://\(domain)")
                }
                if let newCookie = HTTPCookie(properties: properties) {
                    storage.setCookie(newCookie)
                }
            }
        }
        
        // Manually add the authToken as a cookie if it exists
        if let token = authToken {
            for domain in domains {
                let cookieProperties: [HTTPCookiePropertyKey: Any] = [
                    .name: "radiko_session",
                    .value: token,
                    .domain: domain,
                    .path: "/",
                    .expires: Date(timeIntervalSinceNow: 86400)
                ]
                if let tokenCookie = HTTPCookie(properties: cookieProperties) {
                    storage.setCookie(tokenCookie)
                }
            }
        }
        print("Synchronized cookies and manual session to smartstream domains")
    }
    
    private func auth1() async throws -> (Int, Int) {
        let url = URL(string: "https://radiko.jp/v2/api/auth1")!
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw RadikoError.auth1Failed("Invalid response type")
        }
        
        print("auth1 Status: \(httpResponse.statusCode)")
        
        if httpResponse.statusCode != 200 {
            throw RadikoError.auth1Failed("HTTP Status: \(httpResponse.statusCode)")
        }
        
        let token = httpResponse.value(forHTTPHeaderField: "X-Radiko-Authtoken")
        let keyLengthStr = httpResponse.value(forHTTPHeaderField: "X-Radiko-KeyLength")
        let keyOffsetStr = httpResponse.value(forHTTPHeaderField: "X-Radiko-KeyOffset")
        
        print("auth1 Headers - Token: \(token != nil), Length: \(keyLengthStr ?? "nil"), Offset: \(keyOffsetStr ?? "nil")")
        
        guard let token = token,
              let lengthStr = keyLengthStr, let keyLength = Int(lengthStr),
              let offsetStr = keyOffsetStr, let keyOffset = Int(offsetStr) else {
            throw RadikoError.auth1Failed("Missing or invalid headers: token=\(token != nil), length=\(keyLengthStr ?? "nil"), offset=\(keyOffsetStr ?? "nil")")
        }
        
        self.authToken = token
        return (keyLength, keyOffset)
    }
    
    private func getPartialKey(offset: Int, length: Int) throws -> String {
        let keyBytes = Array(PC_FULL_KEY.utf8)
        let end = min(offset + length, keyBytes.count)
        let partialBytes = keyBytes[offset..<end]
        return Data(partialBytes).base64EncodedString()
    }
    
    private func auth2(partialKey: String) async throws -> String {
        guard let token = authToken else { throw RadikoError.auth1Failed("No token") }
        
        let url = URL(string: "https://radiko.jp/v2/api/auth2")!
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.addValue(token, forHTTPHeaderField: "X-Radiko-Authtoken")
        request.addValue(partialKey, forHTTPHeaderField: "X-Radiko-Partialkey")
        
        print("auth2 Starting with token: \(token.prefix(10))...")
        
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw RadikoError.auth2Failed("Invalid response type")
        }
        
        print("auth2 Status: \(httpResponse.statusCode)")
        print("auth2 Headers: \(httpResponse.allHeaderFields.keys.map { String(describing: $0) })")
        
        if let cookieHeaders = httpResponse.allHeaderFields["Set-Cookie"] as? String {
             print("auth2 Set-Cookie found")
        }
        
        // Debug all cookies in storage
        if let cookies = HTTPCookieStorage.shared.cookies {
            print("Total cookies in storage: \(cookies.count)")
            for c in cookies {
                if c.domain.contains("radiko") {
                    print(" - Radiko Cookie: \(c.name) (\(c.domain))")
                }
            }
        }
        
        let body = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        print("auth2 Body: \(body)")
        
        if body == "OUT" {
            throw RadikoError.areaOutside
        }
        
        let areaId = body.components(separatedBy: ",").first ?? ""
        return areaId
    }
    
    func fetchStations(areaId: String) async throws -> [Station] {
        let url = URL(string: "https://radiko.jp/v3/station/list/\(areaId).xml")!
        let (data, _) = try await session.data(from: url)
        
        let parser = StationXMLParser(data: data)
        return parser.parse()
    }
    
    func fetchPrograms(stationId: String, date: String) async throws -> [Program] {
        let url = URL(string: "https://radiko.jp/v3/program/station/date/\(date)/\(stationId).xml")!
        let (data, _) = try await session.data(from: url)
        
        let parser = ProgramXMLParser(data: data, stationId: stationId)
        return parser.parse()
    }
}

// MARK: - XML Parsers

private class StationXMLParser: NSObject, XMLParserDelegate {
    private let parser: XMLParser
    private var stations: [Station] = []
    private var currentElement = ""
    private var currentId = ""
    private var currentName = ""
    
    init(data: Data) {
        self.parser = XMLParser(data: data)
        super.init()
        self.parser.delegate = self
    }
    
    func parse() -> [Station] {
        parser.parse()
        return stations
    }
    
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String : String] = [:]) {
        currentElement = elementName
    }
    
    func parser(_ parser: XMLParser, foundCharacters string: String) {
        let data = string.trimmingCharacters(in: .whitespacesAndNewlines)
        if data.isEmpty { return }
        
        if currentElement == "id" {
            currentId += data
        } else if currentElement == "name" {
            currentName += data
        }
    }
    
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "station" {
            stations.append(Station(id: currentId, name: currentName))
            currentId = ""
            currentName = ""
        }
    }
}

private class ProgramXMLParser: NSObject, XMLParserDelegate {
    private let parser: XMLParser
    private let stationId: String
    private var programs: [Program] = []
    
    private var currentProgramId = ""
    private var currentStartTime = ""
    private var currentEndTime = ""
    private var currentTitle = ""
    private var currentDescription = ""
    private var currentPerformer = ""
    private var currentImageUrl: String?
    
    private var currentElement = ""
    
    init(data: Data, stationId: String) {
        self.parser = XMLParser(data: data)
        self.stationId = stationId
        super.init()
        self.parser.delegate = self
    }
    
    func parse() -> [Program] {
        parser.parse()
        return programs
    }
    
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String : String] = [:]) {
        currentElement = elementName
        if elementName == "prog" {
            let baseId = attributeDict["id"] ?? ""
            currentStartTime = attributeDict["ft"] ?? ""
            currentEndTime = attributeDict["to"] ?? ""
            currentProgramId = "\(baseId)_\(currentStartTime)"
        }
    }
    
    func parser(_ parser: XMLParser, foundCharacters string: String) {
        let data = string.trimmingCharacters(in: .whitespacesAndNewlines)
        if data.isEmpty { return }
        
        switch currentElement {
        case "title": currentTitle += data
        case "desc": currentDescription += data
        case "pfm": currentPerformer += data
        case "img": currentImageUrl = (currentImageUrl ?? "") + data
        default: break
        }
    }
    
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "prog" {
            programs.append(Program(
                id: currentProgramId,
                stationId: stationId,
                startTime: currentStartTime,
                endTime: currentEndTime,
                title: currentTitle,
                description: currentDescription,
                performer: currentPerformer,
                imageUrl: currentImageUrl
            ))
            // Reset for next program
            currentTitle = ""
            currentDescription = ""
            currentPerformer = ""
            currentImageUrl = nil
        }
    }
}
