import { useState } from "react";
import SearchView from "./components/SearchView";
import FlowView from "./components/FlowView";
import "./App.css";

type Tab = "search" | "flow";

export default function App() {
  const [tab, setTab] = useState<Tab>("search");

  return (
    <div className="app">
      <header>
        <h1>MoSes Monitor</h1>
        <nav>
          <button onClick={() => setTab("search")} disabled={tab === "search"}>
            Search
          </button>
          <button onClick={() => setTab("flow")} disabled={tab === "flow"}>
            Flow
          </button>
        </nav>
      </header>
      <main>{tab === "search" ? <SearchView /> : <FlowView />}</main>
    </div>
  );
}
