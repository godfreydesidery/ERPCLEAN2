import '../core/json.dart';

/// A customer (subset of `CustomerDto`). The walk-in/cash customer
/// (`customerKind == CASH_WALK_IN`) is the POS default (AS-3).
class Customer {
  Customer({
    required this.id,
    required this.uid,
    required this.code,
    required this.displayName,
    required this.customerKind,
    required this.defaultCurrency,
    required this.status,
  });

  final String id;
  final String uid;
  final String code;
  final String displayName;
  final String customerKind;
  final String? defaultCurrency;
  final String status;

  bool get isWalkIn => customerKind == 'CASH_WALK_IN';

  factory Customer.fromJson(Map<String, dynamic> j) => Customer(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        code: asStrOr(j['code']),
        displayName: asStrOr(j['displayName']),
        customerKind: asStrOr(j['customerKind']),
        defaultCurrency: asStr(j['defaultCurrency']),
        status: asStrOr(j['status'], 'ACTIVE'),
      );
}

/// A sales agent (subset of `AgentDto`). A numeric agent id is required on every
/// sale (AS-3); the client may collect it though the posted agent currently
/// defaults to the logged-in user (OUT-5).
class Agent {
  Agent({
    required this.id,
    required this.uid,
    required this.code,
    required this.displayName,
    required this.agentKind,
    required this.status,
  });

  final String id;
  final String uid;
  final String code;
  final String displayName;
  final String agentKind;
  final String status;

  factory Agent.fromJson(Map<String, dynamic> j) => Agent(
        id: asStrOr(j['id']),
        uid: asStrOr(j['uid']),
        code: asStrOr(j['code']),
        displayName: asStrOr(j['displayName']),
        agentKind: asStrOr(j['agentKind']),
        status: asStrOr(j['status'], 'ACTIVE'),
      );
}
