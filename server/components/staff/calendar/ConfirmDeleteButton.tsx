"use client";

export function ConfirmDeleteButton({ action, message = "Удалить это событие?" }: { action: (formData: FormData) => void; message?: string }) {
  return (
    <button className="calendarDanger" type="submit" formAction={action} onClick={(event) => {
      if (!window.confirm(message)) event.preventDefault();
    }}>
      Удалить
    </button>
  );
}
