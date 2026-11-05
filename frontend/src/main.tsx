import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  BankPage,
  BatchPage,
  EntryPage,
  EventPage,
  IssuePage,
  OverviewPage,
  PaymentPage,
  ReconPage,
  StatementPage,
} from "./pages";
import "./style.css";
import {
  BatchLinePage,
  CorrectionPage,
  CorrectionsPage,
  LedgerEntryPage,
} from "./corrections";
import { DemoPage } from "./demo";
import { PayoutPage, PayoutsPage } from "./payouts";
import { RefundPage } from "./refunds";

function currentRoute() {
  return window.location.hash.slice(1) || window.location.pathname;
}
/** The public demo exists only under the demo profile; elsewhere its read endpoint is a 404. */
function useDemoEnabled(): boolean | null {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/demo/archive", { signal: controller.signal })
      .then((response) => setEnabled(response.ok))
      .catch(() => {
        if (!controller.signal.aborted) setEnabled(false);
      });
    return () => controller.abort();
  }, []);
  return enabled;
}
function Route({ route, demo }: { route: string; demo: boolean }) {
  const [path, search = ""] = route.split("?");
  let parts: string[];
  try {
    parts = path.split("/").slice(1).map(decodeURIComponent);
  } catch {
    return <p role="alert">页面地址无效</p>;
  }
  const [kind, id, third, fourth] = parts;
  if (path === "/") return demo ? <DemoPage /> : <OverviewPage />;
  if (path === "/demo")
    return demo ? <DemoPage /> : <p role="alert">这个部署没有开启演示页</p>;
  if (path === "/overview") return <OverviewPage />;
  if (path === "/corrections") return <CorrectionsPage />;
  if (path === "/payouts") return <PayoutsPage />;
  if (kind === "batch-lines" && id && third && parts.length === 3)
    return <BatchLinePage id={id} seq={third} />;
  if (kind === "recon" && parts.length === 1)
    return <ReconPage search={search} />;
  if (id && parts.length === 2) {
    if (kind === "corrections") return <CorrectionPage id={id} />;
    if (kind === "entries") return <LedgerEntryPage id={id} />;
    if (kind === "payments") return <PaymentPage id={id} />;
    if (kind === "refunds") return <RefundPage id={id} />;
    if (kind === "payouts") return <PayoutPage id={id} />;
    if (kind === "batches") return <BatchPage id={id} />;
    if (kind === "recon") return <IssuePage id={id} />;
    if (kind === "events") return <EventPage id={id} />;
    if (kind === "bank-lines") return <BankPage id={id} />;
  }
  if (kind === "statement-lines" && id && third && parts.length === 3)
    return <StatementPage file={id} seq={third} />;
  if (
    kind === "recon" &&
    id &&
    third === "entries" &&
    fourth &&
    parts.length === 4
  )
    return <EntryPage issueId={id} entryId={fourth} />;
  return <p role="alert">找不到这个页面</p>;
}
function App() {
  const [route, setRoute] = useState(currentRoute);
  const demo = useDemoEnabled();
  useEffect(() => {
    const changed = () => {
      setRoute(currentRoute());
      window.scrollTo(0, 0);
    };
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
  return (
    <main>
      <header>
        <a href="#/">LedgerLab</a>
        <nav aria-label="主导航">
          {demo && <a href="#/demo">演示</a>}
          <a href={demo ? "#/overview" : "#/"}>总览</a>
          <a href="#/recon">对账工作台</a>
          <a href="#/corrections">纠错记录</a>
          <a href="#/payouts">提现</a>
        </nav>
        <span>合成测试数据 · CAD</span>
      </header>
      {demo === null ? (
        <p role="status">加载中…</p>
      ) : (
        <Route key={route} route={route} demo={demo} />
      )}
    </main>
  );
}
const root = document.getElementById("root");
if (root)
  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
