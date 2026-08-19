import '../core/json.dart';
import '../core/session.dart';

/// A product as the app needs it, flattened from `ProductDto`.
class ProductItem {
  const ProductItem({
    required this.uid,
    required this.code,
    required this.name,
    required this.unit,
    this.categoryName,
  });

  factory ProductItem.fromJson(Map<String, dynamic> j) => ProductItem(
        uid: asStrOr(j['uid']),
        code: asStrOr(j['code']),
        name: asStrOr(j['name']),
        unit: asStrOr(j['baseUnitCode'], asStrOr(j['unitCode'], 'unit')),
        categoryName: asStr(j['categoryName']),
      );

  final String uid;
  final String code;
  final String name;
  final String unit;
  final String? categoryName;
}

class SupplierItem {
  const SupplierItem({required this.uid, required this.name, this.phone});

  factory SupplierItem.fromJson(Map<String, dynamic> j) => SupplierItem(
        uid: asStrOr(j['uid']),
        name: asStrOr(j['displayName'], asStrOr(j['name'])),
        phone: asStr(j['phone']),
      );

  final String uid;
  final String name;
  final String? phone;
}

/// Products, suppliers and the master-data writes.
class CatalogService {
  CatalogService(this.session);

  final Session session;

  /// `/products` takes a numeric `companyId`, not a uid.
  Future<List<ProductItem>> products({String? search}) async {
    final companyId = session.companyId;
    if (companyId == null) return const [];
    final res = await session.api.get('/products', query: {
      'companyId': companyId,
      if (search != null && search.isNotEmpty) 'q': search,
      'size': 100,
    });
    return asList(res, ProductItem.fromJson);
  }

  Future<List<SupplierItem>> suppliers({String? search}) async {
    final companyId = session.companyId;
    if (companyId == null) return const [];
    final res = await session.api.get('/suppliers', query: {
      'companyId': companyId,
      if (search != null && search.isNotEmpty) 'q': search,
      'size': 100,
    });
    return asList(res, SupplierItem.fromJson);
  }

  /// Create a product. `CreateProductRequest` addresses the company by **uid**
  /// (unlike the list endpoint, which wants the numeric id).
  Future<ProductItem> createProduct({
    required String name,
    required String code,
    required String baseUnitCode,
    String? categoryUid,
    String? barcode,
    bool vatable = true,
  }) async {
    final res = await session.api.post('/products', body: {
      'companyUid': session.companyUid,
      'code': code,
      'name': name,
      'productType': 'STOCKABLE',
      'baseUnitCode': baseUnitCode,
      'vatStatus': vatable ? 'STANDARD' : 'EXEMPT',
      if (categoryUid != null) 'categoryUid': categoryUid,
      if (barcode != null && barcode.isNotEmpty) 'barcode': barcode,
    });
    return ProductItem.fromJson(asMap(res));
  }

  /// Create a supplier. `CreateSupplierRequest` takes the numeric `companyId`.
  Future<SupplierItem> createSupplier({
    required String displayName,
    String? phone,
    String? email,
    String? tin,
    bool vatRegistered = false,
    String? physicalAddress,
    int? paymentTermsDays,
  }) async {
    final res = await session.api.post('/suppliers', body: {
      'companyId': session.companyId,
      'partyType': 'ORGANISATION',
      'supplierKind': 'GOODS',
      'displayName': displayName,
      if (phone != null && phone.isNotEmpty) 'phone': phone,
      if (email != null && email.isNotEmpty) 'email': email,
      if (tin != null && tin.isNotEmpty) 'tin': tin,
      'vatRegistered': vatRegistered,
      if (physicalAddress != null && physicalAddress.isNotEmpty)
        'physicalAddress': physicalAddress,
      if (paymentTermsDays != null) 'paymentTermsDays': paymentTermsDays,
    });
    return SupplierItem.fromJson(asMap(res));
  }
}
