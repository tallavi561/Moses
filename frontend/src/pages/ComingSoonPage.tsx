import { useLanguage } from "../i18n/LanguageContext";

export default function ComingSoonPage() {
  const { t } = useLanguage();

  return (
    <div className="card empty-state">
      <h2>{t.comingSoon.title}</h2>
      <p>{t.comingSoon.body}</p>
    </div>
  );
}
