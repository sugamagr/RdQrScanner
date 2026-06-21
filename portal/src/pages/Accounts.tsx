/**
 * Placeholder for the portal /accounts page. Wave 9 fills the full
 * implementation (account table + edit + Mark Inactive/Delete + CSV
 * bulk upload). This stub exists so Wave 8 can land the
 * types/queries/CSV-parser/nav foundation without breaking the build.
 */
import { Users } from 'lucide-react';

export function AccountsPage() {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
      <Users className="h-14 w-14 text-primary" />
      <h2 className="mt-4 text-lg font-semibold text-ink-primary">
        Accounts — coming next
      </h2>
      <p className="mt-1 max-w-sm text-sm text-ink-secondary">
        Table + CSV bulk upload land in Wave 9. The data layer + types +
        nav entry are wired so any operator-side rd_accounts changes
        already sync to this signed-in browser.
      </p>
    </div>
  );
}
