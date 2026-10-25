import { Navigate, Route, Routes } from "react-router-dom";
import { LanguageProvider } from "./i18n/LanguageContext";
import { ThemeProvider } from "./theme/ThemeContext";
import Layout from "./layout/Layout";
import SearchPage from "./pages/SearchPage";
import FlowPage from "./pages/FlowPage";
import SettingsPage from "./pages/SettingsPage";
import ComingSoonPage from "./pages/ComingSoonPage";
import "./App.css";

export default function App() {
  return (
    <ThemeProvider>
      <LanguageProvider>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<Navigate to="/search" replace />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/flow" element={<FlowPage />} />
            <Route path="/dashboard" element={<ComingSoonPage />} />
            <Route path="/uploads" element={<ComingSoonPage />} />
            <Route path="/settings" element={<SettingsPage />} />
          </Route>
        </Routes>
      </LanguageProvider>
    </ThemeProvider>
  );
}
