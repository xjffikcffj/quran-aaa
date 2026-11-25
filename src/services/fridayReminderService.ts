export async function showFridayReminderNotification(
  onNavigateToKahf?: () => void
): Promise<boolean> {
  const title = 'يوم الجمعة - تذكير بسورة الكهف';
  const body =
    'لا تنسَ قراءة أو الاستماع إلى سورة الكهف يوم الجمعة، فهي نور ما بين الجمعتين.';

  if (typeof window !== 'undefined' && 'Notification' in window) {
    try {
      let permission = Notification.permission;
      if (permission === 'default') {
        permission = await Notification.requestPermission();
      }
      if (permission === 'granted') {
        const notification = new Notification(title, {
          body,
          icon: '/assets/ic_app_foreground_art.jpg',
          dir: 'rtl',
          lang: 'ar',
        });
        notification.onclick = () => {
          window.focus();
          onNavigateToKahf?.();
          notification.close();
        };
        return true;
      }
    } catch {
      // Fallback to in-app toast handled by caller
    }
  }
  return false;
}
