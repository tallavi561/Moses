import { Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./auth/AuthContext";
import RequireAuth from "./auth/RequireAuth";
import { ProjectsProvider } from "./projects/ProjectsContext";
import { LanguageProvider } from "./i18n/LanguageContext";
import { ThemeProvider } from "./theme/ThemeContext";
import Layout from "./layout/Layout";
import LoginPage from "./pages/LoginPage";
import ProjectsListPage from "./pages/ProjectsListPage";
import SearchPage from "./pages/SearchPage";
import FlowPage from "./pages/FlowPage";
import SettingsPage from "./pages/SettingsPage";
import ComingSoonPage from "./pages/ComingSoonPage";
import "./App.css";

export default function App() {
  return (
    <ThemeProvider>
      <LanguageProvider>
        <AuthProvider>
          <ProjectsProvider>
            <Routes>
              <Route path="/login" element={<LoginPage />} />

              <Route element={<RequireAuth />}>
                <Route path="/projects" element={<ProjectsListPage />} />

                <Route path="/p/:projectId" element={<Layout />}>
                  <Route index element={<Navigate to="search" replace />} />
                  <Route path="search" element={<SearchPage />} />
                  <Route path="flow" element={<FlowPage />} />
                  <Route path="dashboard" element={<ComingSoonPage />} />
                  <Route path="uploads" element={<ComingSoonPage />} />
                  <Route path="settings" element={<SettingsPage />} />
                </Route>

                <Route path="/" element={<Navigate to="/projects" replace />} />
              </Route>

              <Route path="*" element={<Navigate to="/projects" replace />} />
            </Routes>
          </ProjectsProvider>
        </AuthProvider>
      </LanguageProvider>
    </ThemeProvider>
  );
}
