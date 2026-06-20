/// Helpers for the ERP's `ApiResponse<T>` envelope: `{ data, errors, meta }`.
/// Success responses carry `errors: []` and the payload in `data` (§00 §3).
library;

/// Returns the `data` field from an envelope body, or the body itself if it is
/// not enveloped (defensive — every ERP endpoint is enveloped).
dynamic unwrapData(dynamic body) {
  if (body is Map && body.containsKey('data') && body.containsKey('errors')) {
    return body['data'];
  }
  return body;
}

/// A page of results. The ERP returns Spring-style pages inside `data` as
/// `{ content: [...], number, size, totalElements, totalPages }`. Tolerates a
/// bare list too (some list endpoints are not paged).
class Paged<T> {
  const Paged({
    required this.items,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
  });

  final List<T> items;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;

  bool get isLast => page >= totalPages - 1;

  factory Paged.fromData(dynamic data, T Function(Map<String, dynamic>) item) {
    if (data is List) {
      final items = data
          .whereType<Map>()
          .map((m) => item(m.cast<String, dynamic>()))
          .toList();
      return Paged(
        items: items,
        page: 0,
        size: items.length,
        totalElements: items.length,
        totalPages: 1,
      );
    }
    if (data is Map) {
      final content = (data['content'] as List? ?? const [])
          .whereType<Map>()
          .map((m) => item(m.cast<String, dynamic>()))
          .toList();
      return Paged(
        items: content,
        page: (data['number'] as num?)?.toInt() ?? 0,
        size: (data['size'] as num?)?.toInt() ?? content.length,
        totalElements:
            (data['totalElements'] as num?)?.toInt() ?? content.length,
        totalPages: (data['totalPages'] as num?)?.toInt() ?? 1,
      );
    }
    return Paged(
        items: const [], page: 0, size: 0, totalElements: 0, totalPages: 0);
  }
}
