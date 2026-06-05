import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import {
  apiResponseInterceptor,
  authHeaderInterceptor,
} from './core/api/http.interceptors';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    // Order: attach auth/branch headers on the way out, unwrap the envelope on the way back.
    provideHttpClient(
      withInterceptors([authHeaderInterceptor, apiResponseInterceptor]),
    ),
  ],
};
