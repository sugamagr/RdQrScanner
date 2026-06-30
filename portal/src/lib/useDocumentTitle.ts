import { useEffect } from 'react';

/**
 * Set document.title for the current route and restore the global
 * default when the component unmounts. Without per-route titles the
 * browser tab + history stack + screen-reader landmark all collapse
 * to a single 'RD Scanner — Portal' string, which destroys
 * multi-tab disambiguation and back-navigation context.
 * R6 oracle bg_ee635610 per-page title gap.
 */
const DEFAULT_TITLE = 'RD Scanner — Portal';

export function useDocumentTitle(title: string): void {
  useEffect(() => {
    const previous = document.title;
    document.title = `${title} — RD Scanner`;
    return () => {
      document.title = previous || DEFAULT_TITLE;
    };
  }, [title]);
}
