import Foundation
import Security

struct WidgetSnapshot: Codable {
    let date: Date
    let stamp: String
    let events: [CalendarEvent]
    let message: String
    var monthEvents: [CalendarEvent]? = nil
}

enum SharedStore {
    static var defaults: UserDefaults {
        guard let group=Bundle.main.object(forInfoDictionaryKey:"FamicalAppGroup") as? String,
              let values=UserDefaults(suiteName:group) else { return .standard }
        return values
    }
    static var server: URL? { defaults.string(forKey:"server").flatMap { ServerAddress.normalize($0) } }
    static var team: String { defaults.string(forKey:"team") == "B" ? "B" : "A" }
    static var shiftEnabled: Bool { defaults.object(forKey:"shiftEnabled") as? Bool ?? true }
    static var includePrivate: Bool { defaults.bool(forKey:"includePrivate") }
    static var snapshot: WidgetSnapshot? {
        get { defaults.data(forKey:"snapshot").flatMap { try? JSONDecoder().decode(WidgetSnapshot.self,from:$0) } }
        set { if let newValue,let data=try? JSONEncoder().encode(newValue){defaults.set(data,forKey:"snapshot")}else{defaults.removeObject(forKey:"snapshot")} }
    }
    private static var query: [String:Any] {
        var q: [String:Any] = [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"org.famical.widget.session",kSecAttrAccount as String:"calendar-session"]
        if let group=Bundle.main.object(forInfoDictionaryKey:"FamicalKeychainGroup") as? String,!group.isEmpty { q[kSecAttrAccessGroup as String]=group }
        return q
    }
    static var cookie: String? {
        var q=query;q[kSecReturnData as String]=true;q[kSecMatchLimit as String]=kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary,&result)==errSecSuccess,let data=result as? Data else{return nil}
        return String(data:data,encoding:.utf8)
    }
    @discardableResult static func setCookie(_ value: String?) -> Bool {
        if cookie == value { return true }
        snapshot=nil;SecItemDelete(query as CFDictionary)
        guard let value else{return true}
        var q=query;q[kSecValueData as String]=Data(value.utf8)
        q[kSecAttrAccessible as String]=kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary,nil)==errSecSuccess
    }
    static func setServer(_ value: URL) {
        if server != value {_ = setCookie(nil);snapshot=nil}
        defaults.set(value.absoluteString,forKey:"server")
    }
}
