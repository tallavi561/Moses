import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

export default function ComingSoonPage() {
  const { t } = useLanguage();

  return (
    <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-xl flex flex-col items-center text-center gap-space-sm">
      <div className="w-14 h-14 rounded-2xl bg-surface-container-high flex items-center justify-center text-secondary">
        <Icon name="rocket_launch" size={28} />
      </div>
      <h2 className="font-headline text-headline-md text-on-surface">{t.comingSoon.title}</h2>
      <p className="font-body-md text-body-md text-on-surface-variant max-w-md">{t.comingSoon.body}</p>
    </div>
  );
}
