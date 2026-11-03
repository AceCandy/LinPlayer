import SwiftUI

@MainActor final class ImageLoader: ObservableObject {
    @Published private(set) var revision = 0
    private var base: String?
    private var token: String?
    private var server: String?
    private var generation = UUID()
    private let configuration: URLSessionConfiguration
    private var session: URLSession
    private let cache = NSCache<NSString, UIImage>()

    init(configuration: URLSessionConfiguration = .ephemeral) {
        self.configuration = configuration
        session = URLSession(configuration: configuration)
        cache.totalCostLimit = 32 * 1024 * 1024
    }
    func setChannel(base: String?, token: String?) { self.base = base; self.token = token; revision += 1 }
    func use(server: String) { clearIdentity(); self.server = server }
    func clearIdentity() {
        generation = UUID(); server = nil; cache.removeAllObjects()
        session.invalidateAndCancel(); session = URLSession(configuration: configuration)
        revision += 1
    }

    func load(id: String, kind: String = "Primary", height: Int = 450, server sourceServer: String? = nil) async -> UIImage? {
        guard base != nil, token != nil, let server else { return nil }
        let upstreamServer = sourceServer ?? server
        let key = "\(upstreamServer):\(id):\(kind):\(height)" as NSString
        if let image = cache.object(forKey: key) { return image }
        guard var source = URLComponents(string: upstreamServer),
              ["http", "https"].contains(source.scheme ?? ""), source.host != nil else { return nil }
        source.path = source.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .split(separator: "/").map(String.init).reduce("") { $0 + "/" + $1 } + "/Items/\(id)/Images/\(kind)"
        source.query = "quality=90"
        guard let upstream = source.url?.absoluteString else { return nil }
        return await fetch(upstream, height: height, key: key)
    }

    func loadURL(_ upstream: String, height: Int) async -> UIImage? {
        guard let url = URL(string: upstream), ["http", "https"].contains(url.scheme ?? ""), url.host != nil else { return nil }
        return await fetch(upstream, height: height, key: "url:\(upstream):\(height)" as NSString)
    }

    private func fetch(_ upstream: String, height: Int, key: NSString) async -> UIImage? {
        guard let base, let token, server != nil else { return nil }
        let epoch = generation
        if let image = cache.object(forKey: key) { return image }
        guard var local = URLComponents(string: base + "/img") else { return nil }
        local.queryItems = [URLQueryItem(name: "src", value: upstream), URLQueryItem(name: "h", value: String(height))]
        guard let url = local.url else { return nil }
        var req = URLRequest(url: url); req.setValue(token, forHTTPHeaderField: "X-LP-Token")
        do {
            let (data, response) = try await session.data(for: req)
            try Task.checkCancellation()
            guard epoch == generation, (response as? HTTPURLResponse)?.statusCode == 200,
                  let image = UIImage(data: data) else { return nil }
            cache.setObject(image, forKey: key, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4))
            return image
        } catch { return nil }
    }
}

struct Poster: View {
    let id: String
    let enabled: Bool
    @ObservedObject var loader: ImageLoader
    var width: CGFloat = 190
    var height: CGFloat = 285
    var fit = false
    var placeholder: String? = nil
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(Color.white.opacity(0.08))
            if let image {
                if fit { Image(uiImage: image).resizable().scaledToFit() }
                else { Image(uiImage: image).resizable().scaledToFill() }
            }
            else if let placeholder { Text(placeholder).font(.system(size: 24, weight: .medium)).lineLimit(2).padding(24) }
            else { Image(systemName: "film").font(.largeTitle).foregroundStyle(.secondary) }
        }
        .frame(width: width, height: height).clipped().clipShape(RoundedRectangle(cornerRadius: 12))
        .task(id: "\(id):\(enabled):\(loader.revision)") {
            image = nil
            if enabled {
                let loaded = await loader.load(id: id)
                guard !Task.isCancelled else { return }
                image = loaded
            }
        }
        .accessibilityHidden(true)
    }
}

struct MediaArtwork: View {
    let item: MediaItem
    @ObservedObject var loader: ImageLoader
    let width: CGFloat
    let height: CGFloat
    var server: String? = nil
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Color.primary.opacity(0.06)
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Text(item.type_ == "Season" ? "暂无季封面" : "暂无封面").font(.system(size: 22)).foregroundStyle(.secondary) }
        }.frame(width: width, height: height).clipped().clipShape(RoundedRectangle(cornerRadius: 12))
        .task(id: "\(server ?? ""):\(item):\(loader.revision)") {
            image = nil
            for source in item.artworkSources {
                let loaded = await loader.load(id: source.id, kind: source.kind, server: server)
                guard !Task.isCancelled else { return }
                if let loaded { image = loaded; break }
            }
        }.accessibilityHidden(true)
    }
}

struct Backdrop: View {
    let item: MediaItem
    @ObservedObject var loader: ImageLoader
    var height: CGFloat = 460
    var primaryEnabled: Bool? = nil
    var whiteForeground = false
    var heroForeground = false
    @EnvironmentObject private var model: AppModel
    @State private var image: UIImage?
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                TVPalette.background(model.privateSpace)
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                        .frame(width: geometry.size.width, height: height).clipped()
                }
                LinearGradient(stops: whiteForeground ? [
                    .init(color: .black.opacity(0.45), location: 0),
                    .init(color: .black.opacity(0.1), location: 0.25),
                    .init(color: .black.opacity(heroForeground ? 0.9 : 0.7), location: 0.65),
                    .init(color: .black.opacity(heroForeground ? 1 : 0.96), location: 0.92),
                    .init(color: TVPalette.background(model.privateSpace), location: 1)
                ] : [
                    .init(color: TVPalette.background(model.privateSpace).opacity(0.45), location: 0),
                    .init(color: TVPalette.background(model.privateSpace).opacity(0.1), location: 0.25),
                    .init(color: TVPalette.background(model.privateSpace).opacity(0.7), location: 0.72),
                    .init(color: TVPalette.background(model.privateSpace), location: 1)
                ], startPoint: .top, endPoint: .bottom)
            }.frame(width: geometry.size.width, height: height).clipped()
        }.frame(height: height)
        .task(id: "\(item.id):\(String(describing: item.has_backdrop)):\(primaryEnabled ?? item.has_primary):\(loader.revision)") {
            image = nil
            var loaded: UIImage?
            if item.has_backdrop == true { loaded = await loader.load(id: item.id, kind: "Backdrop/0", height: 720) }
            guard !Task.isCancelled else { return }
            if loaded == nil && (primaryEnabled ?? item.has_primary) { loaded = await loader.load(id: item.id, height: 720) }
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }
}

struct RemoteArtwork: View {
    let url: String?
    @ObservedObject var loader: ImageLoader
    let width: CGFloat
    let height: CGFloat
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Color.primary.opacity(0.06)
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Image(systemName: "photo").foregroundStyle(.secondary) }
        }.frame(width: width, height: height).clipped().clipShape(RoundedRectangle(cornerRadius: 12))
        .task(id: "\(url ?? ""):\(loader.revision)") {
            image = nil
            if let url {
                let loaded = await loader.loadURL(url, height: Int(height * 2))
                guard !Task.isCancelled else { return }; image = loaded
            }
        }.accessibilityHidden(true)
    }
}
