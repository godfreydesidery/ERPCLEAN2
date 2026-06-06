import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import {
  apiResponseInterceptor,
  authErrorInterceptor,
  authHeaderInterceptor,
} from './core/api/http.interceptors';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    // Order (outer → inner on the way out): catch 401s and bounce to login, attach auth/branch
    // headers, then unwrap the envelope on the way back.
    provideHttpClient(
      withInterceptors([authErrorInterceptor, authHeaderInterceptor, apiResponseInterceptor]),
    ),
  ],
};
