import Foundation
import Observation

/// Birth date, height and weight from «О тебе» («body-profile.json»); demo players get a fixed demo profile.
@Observable
final class BodyProfileStore {
    private(set) var profile: BodyProfile?
    private let storage: JSONFileStorage<BodyProfile>?

    init(storage: JSONFileStorage<BodyProfile>? = JSONFileStorage(filename: "body-profile.json")) {
        self.storage = storage
        self.profile = storage?.load()
    }

    func reload(user: User?) {
        profile = storage?.load() ?? (user?.isDemo == true ? .demo : nil)
    }

    func save(_ newProfile: BodyProfile) {
        profile = newProfile
        storage?.save(newProfile)
    }
}
