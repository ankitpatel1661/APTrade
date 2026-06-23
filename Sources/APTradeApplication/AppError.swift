public enum AppError: Error, Equatable, Sendable {
    case network
    case notFound
    case decoding
    case rateLimited
}
