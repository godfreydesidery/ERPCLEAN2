// Orbix App bootstrap (template - Flutter fills in the two tokens at build).
{{flutter_js}}
{{flutter_build_config}}

// The engine's Roboto is served from this site (web/fonts/), not from
// fonts.gstatic.com: works offline and keeps the CSP to 'self'. With a custom
// onEntrypointLoaded the engine reads its settings from initializeEngine(),
// so the same object goes to both.
const orbixEngineConfig = { fontFallbackBaseUrl: 'fonts/' };

_flutter.loader.load({
  config: orbixEngineConfig,
  onEntrypointLoaded: async function (engineInitializer) {
    const appRunner = await engineInitializer.initializeEngine(orbixEngineConfig);
    await appRunner.runApp();
    if (window.orbixPwa) window.orbixPwa.appStarted();
  },
});
