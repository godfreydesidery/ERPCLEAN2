import '../core/json.dart';

/// `GET /organisations/current` — the deployment's single organisation.
class Organisation {
  Organisation({required this.uid, required this.name});
  final String uid;
  final String name;

  factory Organisation.fromJson(Map<String, dynamic> j) => Organisation(
        uid: asStrOr(j['uid']),
        name: asStrOr(j['name']),
      );
}

/// `GET /companies/accessible` — a company the caller may act in (id + uid).
class Company {
  Company({
    required this.id,
    required this.uid,
    required this.name,
    this.baseCurrency,
    this.taxId,
    this.vrn,
    this.contactPhone,
    this.contactEmail,
    this.addressLine1,
    this.addressLine2,
    this.city,
    this.region,
    this.country,
  });
  final String id;
  final String uid;
  final String name;
  final String? baseCurrency;

  // Fiscal-receipt header fields (all optional — older/unmigrated companies
  // may not have them populated yet).
  final String? taxId;
  final String? vrn;
  final String? contactPhone;
  final String? contactEmail;
  final String? addressLine1;
  final String? addressLine2;
  final String? city;
  final String? region;
  final String? country;

  factory Company.fromJson(Map<String, dynamic> j) => Company(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        name: asStrOr(j['name']),
        baseCurrency: asStr(j['baseCurrency']),
        taxId: asStr(j['taxId']),
        vrn: asStr(j['vrn']),
        contactPhone: asStr(j['contactPhone']),
        contactEmail: asStr(j['contactEmail']),
        addressLine1: asStr(j['addressLine1']),
        addressLine2: asStr(j['addressLine2']),
        city: asStr(j['city']),
        region: asStr(j['region']),
        country: asStr(j['country']),
      );
}

/// `GET /branches?companyUid` — carries the numeric `id` the POS queries need.
class Branch {
  Branch({
    required this.id,
    required this.uid,
    required this.companyId,
    required this.companyUid,
    required this.code,
    required this.name,
    required this.isDefault,
    required this.status,
  });
  final String id;
  final String uid;
  final String companyId;
  final String companyUid;
  final String code;
  final String name;
  final bool isDefault;
  final String status;

  bool get isActive => status == 'ACTIVE';

  factory Branch.fromJson(Map<String, dynamic> j) => Branch(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        companyId: asStrOr(j['companyId']),
        companyUid: asStrOr(j['companyUid']),
        code: asStrOr(j['code']),
        name: asStrOr(j['name']),
        isDefault: asBool(j['isDefault']),
        status: asStrOr(j['status'], 'ACTIVE'),
      );
}

/// The fully-resolved operating context for a POS shift: the org, the active
/// company (numeric id), and the active branch (numeric id). Built once after
/// login from `/organisations/current` + `/companies/accessible` + `/branches`.
class PosContext {
  PosContext({
    required this.organisationUid,
    required this.company,
    required this.branch,
    this.branchFallback = false,
  });

  final String organisationUid;
  final Company company;
  final Branch branch;

  /// True when [branch] is NOT the caller's requested/active branch — i.e. the
  /// intended branch couldn't be confirmed and resolution fell back to the
  /// company default (or the first branch). Surfaced to the operator as a
  /// non-blocking warning so a cashier notices they may be on the wrong branch.
  final bool branchFallback;

  String get companyId => company.id;
  String get companyUid => company.uid;
  String get branchId => branch.id;
  String get branchUid => branch.uid;
}
