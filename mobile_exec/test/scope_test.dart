import 'package:flutter_test/flutter_test.dart';
import 'package:orbix_hq/core/scope.dart';
import 'package:orbix_hq/services/operations_service.dart';

BranchRef _b(String uid, String company, {bool isDefault = false}) =>
    BranchRef(
      uid: uid,
      name: 'Branch $uid',
      code: uid.toUpperCase(),
      companyUid: company,
      isDefault: isDefault,
    );

SessionRow _s(String uid, {String? branchId, String? branchName}) =>
    SessionRow.fromJson({
      'uid': uid,
      'tillName': 'Till',
      'cashierName': 'Cashier',
      'openedAt': '2026-11-25T08:00:00Z',
      'status': 'OPEN',
      'openingFloatAmount': 0,
      'branchId': branchId,
      'branchName': branchName,
    });

void main() {
  group('pickInitialBranch', () {
    final branches = [
      _b('a1', 'A'),
      _b('a2', 'A', isDefault: true),
      _b('b1', 'B'),
    ];

    test('returns the saved branch while the user is still assigned', () {
      expect(pickInitialBranch(branches, 'b1')!.uid, 'b1');
    });

    test('falls back to the default when the saved branch was revoked', () {
      expect(pickInitialBranch(branches, 'gone')!.uid, 'a2');
    });

    test('falls back to the default with nothing saved', () {
      expect(pickInitialBranch(branches, null)!.uid, 'a2');
    });

    test('takes the first when no branch is marked default', () {
      expect(pickInitialBranch([_b('x', 'A'), _b('y', 'A')], null)!.uid, 'x');
    });

    test('is null with no branches', () {
      expect(pickInitialBranch(const [], 'a1'), isNull);
    });
  });

  test('branchesByCompany keeps first-seen company order', () {
    final groups = branchesByCompany([
      _b('b1', 'B'),
      _b('a1', 'A'),
      _b('b2', 'B'),
    ]);
    expect(groups.map((g) => g.companyUid), ['B', 'A']);
    expect(groups.first.branches.map((b) => b.uid), ['b1', 'b2']);
  });

  test('scopeKeyOf changes with the company as well as the branch', () {
    expect(scopeKeyOf('A', 'x'), isNot(scopeKeyOf('B', 'x')));
    expect(scopeKeyOf('A', 'x'), isNot(scopeKeyOf('A', 'y')));
    expect(scopeKeyOf('A', 'x'), scopeKeyOf('A', 'x'));
  });

  group('sessionsAtBranch', () {
    final rows = [
      _s('1', branchId: '10', branchName: 'Mbezi'),
      _s('2', branchId: '11', branchName: 'Kariakoo'),
      _s('3', branchId: '10', branchName: 'Mbezi'),
    ];

    test('matches on the numeric id when known', () {
      final out = sessionsAtBranch(rows, branchId: '10', branchName: 'x');
      expect(out.map((r) => r.uid), ['1', '3']);
    });

    test('matches on the name when the id is unknown', () {
      final out = sessionsAtBranch(rows, branchName: ' kariakoo ');
      expect(out.map((r) => r.uid), ['2']);
    });

    test('drops another branch\'s tills rather than showing them', () {
      expect(sessionsAtBranch(rows, branchId: '99'), isEmpty);
    });

    test('returns everything when there is no branch to match', () {
      expect(sessionsAtBranch(rows), hasLength(3));
    });
  });
}
