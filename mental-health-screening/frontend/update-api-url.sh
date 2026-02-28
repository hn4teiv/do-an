

if [ -z "$1" ]; then
  echo " Thiếu URL Spring Boot!"
  echo "   Usage: ./update-api-url.sh https://your-app.onrender.com"
  exit 1
fi

NEW_URL="$1"
HTML_DIR="${2:-.}"   # thư mục HTML, mặc định thư mục hiện tại

echo "🔄 Đang thay localhost:8080 → $NEW_URL"
echo "   Trong thư mục: $HTML_DIR"
echo ""

# Thay thế trong tất cả file HTML
for file in "$HTML_DIR"/*.html; do
  if grep -q "localhost:8080" "$file"; then
    sed -i.bak "s|http://localhost:8080|$NEW_URL|g" "$file"
    rm -f "${file}.bak"
    echo "    $(basename $file)"
  fi
done

echo ""
echo " Xong! Commit và push lên GitHub để GitHub Pages cập nhật."