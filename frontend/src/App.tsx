import { useState } from "react";
import SearchView from "./components/SearchView";
import FlowView from "./components/FlowView";
import "./App.css";

type Tab = "search" | "flow";

export default function App() {
  const [tab, setTab] = useState<Tab>("search");

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-title">
          <div className="logo">MS</div>
          <div>
            <h1>MoSes Monitor</h1>
            <p className="subtitle">ניטור לוגים ותהליכים חכם</p>
          </div>
        </div>

        <nav className="tabs">
          <button
            className={`tab-button ${tab === "search" ? "active" : ""}`}
            onClick={() => setTab("search")}
          >
            חיפוש
          </button>
          <button
            className={`tab-button ${tab === "flow" ? "active" : ""}`}
            onClick={() => setTab("flow")}
          >
            זרימת מערכת
          </button>
        </nav>
      </header>

      <main className="app-main">{tab === "search" ? <SearchView /> : <FlowView />}</main>
    </div>
  );
}
