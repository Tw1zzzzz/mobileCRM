import Foundation

/// Keys built into the app from `Pulse/Secrets.plist` — git-ignored, so they never reach the public repository.
/// A build without the file has none, and players enter their own Faceit key.
enum AppSecrets {
    /// Faceit Data API client-side key (`FaceitAPIKey`).
    static var faceitAPIKey: String? {
        guard let url = Bundle.main.url(forResource: "Secrets", withExtension: "plist"),
              let values = NSDictionary(contentsOf: url) as? [String: Any] else { return nil }
        return values["FaceitAPIKey"] as? String
    }
}
