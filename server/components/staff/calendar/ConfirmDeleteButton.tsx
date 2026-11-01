"use client";

export function ConfirmDeleteButton({ action }: { action: (formData: FormData) => void }) {
  return (
    <button className="calendarDanger" type="submit" formAction={action} onClick={(event) => {
      if (!window.confirm("Удалить это событие?")) event.preventDefault();
    }}>
      Удалить
    </button>
  );
}
