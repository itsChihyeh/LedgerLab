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
import { RefundPage } from "./refunds";

function currentRoute() {
  return window.location.hash.slice(1) || window.location.pathname;
}
function Route({ route }: { route: string }) {
  const [path, search = ""] = route.split("?");
  let parts: string[];
  try {
    parts = path.split("/").slice(1).map(decodeURIComponent);
  } catch {
    return <p role="alert">页面地址无效</p>;
  }
  const [kind, id, third, fourth] = parts;
  if (path === "/") return <OverviewPage />;
  if (path === "/corrections") return <CorrectionsPage />;
  if (kind === "batch-lines" && id && third && parts.length === 3)
    return <BatchLinePage id={id} seq={third} />;
  if (kind === "recon" && parts.length === 1)
    return <ReconPage search={search} />;
  if (id && parts.length === 2) {
    if (kind === "corrections") return <CorrectionPage id={id} />;
    if (kind === "entries") return <LedgerEntryPage id={id} />;
    if (kind === "payments") return <PaymentPage id={id} />;
    if (kind === "refunds") return <RefundPage id={id} />;
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
          <a href="#/">总览</a>
          <a href="#/recon">对账工作台</a>
          <a href="#/corrections">纠错记录</a>
        </nav>
        <span>合成测试数据 · CAD</span>
      </header>
      <Route key={route} route={route} />
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
