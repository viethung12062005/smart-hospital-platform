import { Alert, Button } from '@mantine/core';
import { Component, type ErrorInfo, type ReactNode } from 'react';
import i18n from '@/i18n';

interface State {
  readonly error?: Error;
}

/** Catch render errors in one screen so the whole app does not go blank. Does not show the stack trace to the user. */
export class ErrorBoundary extends Component<{ readonly children: ReactNode }, State> {
  state: State = {};

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('Screen render error', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="shp-page">
          <Alert color="red" title={i18n.t('error.title')}>
            {i18n.t('error.boundary')}
            <div style={{ marginTop: 12 }}>
              <Button size="xs" variant="light" onClick={() => window.location.assign('/')}>
                {i18n.t('nav.home')}
              </Button>
            </div>
          </Alert>
        </div>
      );
    }
    return this.props.children;
  }
}
