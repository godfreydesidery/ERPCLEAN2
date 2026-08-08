import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/api/api_client.dart';
import '../core/api/token_manager.dart';
import '../core/config/app_config.dart';
import '../core/storage/secure_store.dart';
import '../services/auth_service.dart';
import '../services/catalog_service.dart';
import '../services/context_service.dart';
import '../services/party_service.dart';
import '../services/sale_service.dart';
import '../services/session_service.dart';
import '../services/step_up_service.dart';
import '../services/till_service.dart';

/// The ERP host (scheme+host+port). Seeded at startup from persisted config;
/// the Setup screen updates it (and persists), which rebuilds the API client.
class HostController extends Notifier<String> {
  @override
  String build() => AppConfig.defaultHost;
  void set(String host) => state = host;
}

final baseHostProvider =
    NotifierProvider<HostController, String>(HostController.new);

String _apiBase(String host) {
  final h = host.endsWith('/') ? host.substring(0, host.length - 1) : host;
  return '$h/api/v1';
}

final apiBaseProvider =
    Provider<String>((ref) => _apiBase(ref.watch(baseHostProvider)));

final secureStoreProvider = Provider<SecureStore>((ref) => SecureStore());

final tokenManagerProvider = Provider<TokenManager>((ref) {
  return TokenManager(
    apiBase: ref.watch(apiBaseProvider),
    store: ref.watch(secureStoreProvider),
  );
});

final apiClientProvider = Provider<ApiClient>((ref) {
  return ApiClient(
    apiBase: ref.watch(apiBaseProvider),
    tokens: ref.watch(tokenManagerProvider),
  );
});

// ----------------------------------------------------------------- services

final authServiceProvider = Provider<AuthService>(
    (ref) => AuthService(ref.watch(apiClientProvider), ref.watch(tokenManagerProvider)));

final contextServiceProvider =
    Provider<ContextService>((ref) => ContextService(ref.watch(apiClientProvider)));

final catalogServiceProvider =
    Provider<CatalogService>((ref) => CatalogService(ref.watch(apiClientProvider)));

final partyServiceProvider =
    Provider<PartyService>((ref) => PartyService(ref.watch(apiClientProvider)));

final tillServiceProvider =
    Provider<TillService>((ref) => TillService(ref.watch(apiClientProvider)));

final sessionServiceProvider =
    Provider<SessionService>((ref) => SessionService(ref.watch(apiClientProvider)));

final saleServiceProvider =
    Provider<SaleService>((ref) => SaleService(ref.watch(apiClientProvider)));

/// Manager step-up. Rides on the cashier's own token — it verifies a second
/// person's credentials without ever replacing the session.
final stepUpServiceProvider =
    Provider<StepUpService>((ref) => StepUpService(ref.watch(apiClientProvider)));
