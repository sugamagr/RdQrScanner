import { useCallback, useRef, type MouseEvent } from 'react';

/**
 * Backdrop click-to-close handler with drag-release safety.
 *
 * The naive `onClick={onClose}` on the backdrop fires on mouseup —
 * which is the wrong event when the operator started a click INSIDE
 * the dialog content (e.g. selecting text in an input, dragging a
 * slider thumb) and the cursor crossed onto the backdrop before
 * release. That mouseup looks like "clicked the backdrop" to React
 * and dismisses the dialog mid-edit, losing the operator's work.
 *
 * The fix: only treat a click as a backdrop dismissal when BOTH the
 * mousedown AND the click originated on the backdrop element itself
 * (`e.target === e.currentTarget`). Drag-release from inside content
 * onto the backdrop is suppressed. R5 oracle bg_e2aaa5bf F1 verified.
 *
 * Each call returns a fresh `{onMouseDown, onClick}` pair to bind on
 * the backdrop `<div>`. The mousedown-origin flag lives in a ref so
 * back-to-back interactions don't race React's render cycle.
 */
export function useBackdropClose(onClose: () => void) {
  const downOnBackdropRef = useRef(false);

  const onMouseDown = useCallback((e: MouseEvent<HTMLDivElement>) => {
    downOnBackdropRef.current = e.target === e.currentTarget;
  }, []);

  const onClick = useCallback(
    (e: MouseEvent<HTMLDivElement>) => {
      if (e.target === e.currentTarget && downOnBackdropRef.current) {
        onClose();
      }
      downOnBackdropRef.current = false;
    },
    [onClose],
  );

  return { onMouseDown, onClick };
}
