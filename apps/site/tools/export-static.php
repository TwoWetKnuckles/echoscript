<?php

declare(strict_types=1);

use App\Kernel;
use Symfony\Component\HttpFoundation\Request;

require dirname(__DIR__).'/vendor/autoload.php';
(new Symfony\Component\Dotenv\Dotenv())->bootEnv(dirname(__DIR__).'/.env');

if ($argc !== 2 || !is_file(dirname(__DIR__).'/public/assets/manifest.json')) {
    throw new RuntimeException('Compile the site assets and supply an output directory.');
}
$destination = $argv[1];
if (!is_dir($destination) && !mkdir($destination, 0755, true)) {
    throw new RuntimeException('Cannot create the static site output.');
}
$kernel = new Kernel('prod', false);
$request = Request::create('https://localhost/');
$response = $kernel->handle($request);
if ($response->getStatusCode() !== 200) {
    throw new RuntimeException('The public page did not render successfully.');
}
file_put_contents($destination.'/index.html', $response->getContent());
$assets = dirname(__DIR__).'/public/assets';
$files = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($assets, FilesystemIterator::SKIP_DOTS));
foreach ($files as $file) {
    if ($file->isLink() || !$file->isFile()) {
        throw new RuntimeException('Unexpected compiled asset entry.');
    }
    $target = $destination.'/assets/'.substr($file->getPathname(), strlen($assets) + 1);
    if (!is_dir(dirname($target))) {
        mkdir(dirname($target), 0755, true);
    }
    copy($file->getPathname(), $target);
}
$kernel->terminate($request, $response);
$kernel->shutdown();
echo "Exported the Symfony public page and compiled local assets.\n";
