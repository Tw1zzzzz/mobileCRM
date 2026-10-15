import Foundation

/// Where data lives. The first release keeps everything on the phone; switching `backend` to `.remote`
/// turns on server sign-in and sync without touching the screens.
enum AppConfig {
    enum Backend: Equatable {
        /// Accounts and data only on this iPhone (current release).
        case local
        /// Pulse API — the same backend and database as the web version.
        case remote(URL)
    }

    static let backend: Backend = .local

    /// Example for the future: `.remote(URL(string: "https://api.pulse.app/v1")!)`.
    static var apiBaseURL: URL? {
        if case .remote(let url) = backend { return url }
        return nil
    }
}
