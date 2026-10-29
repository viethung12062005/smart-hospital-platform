import dayjs from 'dayjs';
import 'dayjs/locale/vi';
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { en } from './en';
import { vi } from './vi';

export type Lang = 'vi' | 'en';
const STORAGE_KEY = 'shp.lang';

function initialLang(): Lang {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'vi' || saved === 'en') return saved;
  } catch {
    // localStorage bị chặn: dùng mặc định.
  }
  return 'vi';
}

void i18n.use(initReactI18next).init({
  resources: { vi: { translation: vi }, en: { translation: en } },
  lng: initialLang(),
  fallbackLng: 'vi',
  interpolation: { escapeValue: false },
});
dayjs.locale(i18n.language);

export function setLanguage(lang: Lang): void {
  void i18n.changeLanguage(lang);
  dayjs.locale(lang);
  try {
    localStorage.setItem(STORAGE_KEY, lang);
  } catch {
    // bỏ qua
  }
}

/** Ngôn ngữ hiện tại, gửi kèm Accept-Language để máy chủ trả thông báo lỗi đúng ngôn ngữ. */
export function currentLang(): Lang {
  return i18n.language === 'en' ? 'en' : 'vi';
}

export default i18n;
